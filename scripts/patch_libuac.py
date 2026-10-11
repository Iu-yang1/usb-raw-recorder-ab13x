#!/usr/bin/env python3
"""Reproducibly patch pinned nExtCamera/libuac for AB13X UAC1 startup.

The source is checked out by CI at 17a6ba0a09309a6890ec338826de43a256f89813.
This patch fails closed if the expected upstream layout changes. No prebuilt
native libraries or altered third-party commits are used.
"""
from pathlib import Path

source = Path("app/src/main/cpp/vendor/libuac/src/uac_streaming.cpp")
text = source.read_text(encoding="utf-8")
if "RADIO_USB_ISO_DIAGNOSTICS_V1" in text:
    raise SystemExit("libuac patch already applied")

def replace_unique(old, new, label):
    global text
    if text.count(old) != 1:
        raise SystemExit(f"Invalid pinned libuac {label} anchor: found {text.count(old)}")
    text = text.replace(old, new, 1)

replace_unique(
    '#include "uac_exceptions.h"',
    '#include "uac_exceptions.h"\n#include <cerrno>\n#include <chrono>\n#include <string>\n#include <thread>',
    "include",
)

start_anchor = "    void uac_stream_handle_impl::start(stream_cb_func stream_cb_func, int burst) {"
stop_anchor = "    void uac_stream_handle_impl::stop() {"
if text.count(start_anchor) != 1 or text.count(stop_anchor) != 1:
    raise SystemExit("Pinned libuac start/stop methods changed")
start = text.index(start_anchor)
end = text.index(stop_anchor, start)
original = text[start:end]
for required in (
    'set_sampling_freq(target_sampling_rate);',
    'libusb_set_interface_alt_setting(dev_handle->usb_handle',
    'libusb_submit_transfer(transfer)',
    'throw std::runtime_error("No transfers submitted!")'
):
    if required not in original:
        raise SystemExit("Pinned libuac start method incompatible: " + required)

replacement = r'''    void uac_stream_handle_impl::start(stream_cb_func stream_cb_func, int burst) {
        // RADIO_USB_ISO_DIAGNOSTICS_V1; RADIO_USB_AB13X_UAC1_INIT_V2
        // UAC1 endpoint 0x83 on 001F:0B21 is ISO IN, 48 kHz/16-bit/mono.
        // Follow ALSA snd-usb-audio ordering: select streaming altsetting
        // BEFORE sending UAC1 endpoint SET_CUR sampling frequency.
        this->cb_func = std::move(stream_cb_func);
        const int iso_packets = burst;
        const uint16_t wMaxPacketSize = altsetting.endpoint.wMaxPacketSize;
        if (iso_packets < 1 || wMaxPacketSize == 0) {
            throw std::invalid_argument("Invalid isochronous packet parameters");
        }
        const int transfer_size = iso_packets * wMaxPacketSize;
        auto *handle = dev_handle->usb_handle;
        libusb_device_descriptor deviceDesc{};
        const bool knownAB13X =
            libusb_get_device_descriptor(libusb_get_device(handle), &deviceDesc) == LIBUSB_SUCCESS &&
            deviceDesc.idVendor == 0x001f && deviceDesc.idProduct == 0x0b21;

        auto read_alt = [&]() -> int {
            unsigned char value = 0xff;
            const int result = libusb_control_transfer(handle,
                LIBUSB_ENDPOINT_IN | LIBUSB_REQUEST_TYPE_STANDARD | LIBUSB_RECIPIENT_INTERFACE,
                LIBUSB_REQUEST_GET_INTERFACE, 0, bInterfaceNr, &value, 1, 1000);
            return result == 1 ? static_cast<int>(value) :
                (result < 0 ? result : LIBUSB_ERROR_IO);
        };

        std::string configurationLog;
        auto set_alt = [&]() {
            const int err = libusb_set_interface_alt_setting(
                handle, bInterfaceNr, altsetting.bAlternateSetting);
            if (err != LIBUSB_SUCCESS)
                throw usb_exception_impl("libusb_set_interface_alt_setting()", (libusb_error)err);

            if (knownAB13X) {
                int current = read_alt();
                configurationLog += "GET_INTERFACE=" + std::to_string(current) + ";";
                // A successful SET_INTERFACE ioctl does not by itself prove that
                // the USB device and kernel are using the selected alternate setting.
                if (current >= 0 && current != altsetting.bAlternateSetting) {
                    const int retry = libusb_set_interface_alt_setting(
                        handle, bInterfaceNr, altsetting.bAlternateSetting);
                    configurationLog += " altRetry=" + std::to_string(retry) + ";";
                    if (retry != LIBUSB_SUCCESS)
                        throw usb_exception_impl("SET_INTERFACE retry", (libusb_error)retry);
                    current = read_alt();
                    configurationLog += " GET_INTERFACE_retry=" + std::to_string(current) + ";";
                    if (current >= 0 && current != altsetting.bAlternateSetting)
                        throw std::runtime_error("Interface altsetting mismatch after retry");
                }
                // A small settling interval is appropriate for this Full-Speed UAC1
                // device, but is not used as a workaround for kernel permission errors.
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
            }
        };

        const bool samplingControl =
            (altsetting.endpoint.iso_desc.bmAttributes & SAMPLING_FREQ_CONTROL) != 0;
        auto configure_stream = [&](bool recovery) {
            if (recovery) {
                const int off = libusb_set_interface_alt_setting(handle, bInterfaceNr, 0);
                configurationLog += "resetAlt0=" + std::to_string(off) + ";";
                if (off != LIBUSB_SUCCESS)
                    throw usb_exception_impl("recover SET_INTERFACE alt=0", (libusb_error)off);
                std::this_thread::sleep_for(std::chrono::milliseconds(20));
            }

            if (!knownAB13X && samplingControl) {
                // Preserve existing behavior for non-AB13X UAC1 devices.
                set_sampling_freq(target_sampling_rate);
            }
            set_alt();
            if (knownAB13X && samplingControl) {
                // Linux snd-usb-audio sets the streaming interface first for UAC1.
                set_sampling_freq(target_sampling_rate);
                configurationLog += "UAC1_SET_CUR=" + std::to_string(target_sampling_rate) + ";";
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
            }
        };

        std::string errors;
        int allocationFailures = 0;
        int submitFailures = 0;
        mActiveTransfers = 0;

        // A single bounded recovery is allowed for AB13X EPIPE: first
        // deactivate the endpoint, select alt=1 again, set rate, then retry.
        // Never use CLEAR_HALT on an isochronous endpoint.
        const int maxAttempts = knownAB13X ? 2 : 1;
        for (int attempt = 0; attempt < maxAttempts; ++attempt) {
            try {
                configure_stream(attempt > 0);
            } catch (const std::exception& ex) {
                errors += "configure attempt " + std::to_string(attempt) + ": " + ex.what() + ";";
                if (attempt == 0) throw;
                break;
            }

            bool epipeFailure = false;
            for (int i = 0; i < NUM_ISO_TRANSFERS; ++i) {
                libusb_transfer *transfer = libusb_alloc_transfer(iso_packets);
                if (!transfer) {
                    ++allocationFailures;
                    continue;
                }
                uint8_t *buffer = new (std::nothrow) uint8_t[transfer_size];
                if (!buffer) {
                    ++allocationFailures;
                    libusb_free_transfer(transfer);
                    continue;
                }
                memset(buffer, 0, transfer_size);
                libusb_fill_iso_transfer(transfer, handle,
                    altsetting.endpoint.bEndpointAddress, buffer, transfer_size,
                    iso_packets, cb, this, 1000);
                libusb_set_iso_packet_lengths(transfer, wMaxPacketSize);
                errno = 0;
                const int result = libusb_submit_transfer(transfer);
                const int savedErrno = errno;
                if (result == LIBUSB_SUCCESS) {
                    transfers.push_back(transfer);
                    ++mActiveTransfers;
                } else {
                    ++submitFailures;
                    errors += "attempt=" + std::to_string(attempt) +
                        " transfer=" + std::to_string(i) + " " +
                        libusb_error_name(result) +
                        "(errno=" + std::to_string(savedErrno) + ");";
                    delete[] buffer;
                    libusb_free_transfer(transfer);
                    if (knownAB13X && savedErrno == EPIPE && result == LIBUSB_ERROR_IO) {
                        epipeFailure = true;
                        break; // Do not submit seven more identical failing URBs.
                    }
                    if (result == LIBUSB_ERROR_NO_DEVICE) break;
                }
            }
            if (!transfers.empty()) break;
            if (!(knownAB13X && attempt == 0 && epipeFailure)) break;
        }

        if (transfers.empty()) {
            const std::string reason =
                "No ISO transfers submitted: interface=" + std::to_string(bInterfaceNr) +
                ", alt=" + std::to_string(altsetting.bAlternateSetting) +
                ", endpoint=" + std::to_string(altsetting.endpoint.bEndpointAddress) +
                ", wMaxPacketSize=" + std::to_string(wMaxPacketSize) +
                ", packetsPerTransfer=" + std::to_string(iso_packets) +
                ", allocFailed=" + std::to_string(allocationFailures) +
                ", submitFailed=" + std::to_string(submitFailures) +
                ", startup=[" + configurationLog + "] errors=[" + errors + "]";
            const int reset = libusb_set_interface_alt_setting(handle, bInterfaceNr, 0);
            LOG_WARN("No ISO transfers submitted; restoring alt0 returned %s", libusb_error_name(reset));
            throw std::runtime_error(reason);
        }
        active = true;
    }

'''
text = text[:start] + replacement + text[end:]
replace_unique(
    """        libusb_set_interface_alt_setting(dev_handle->usb_handle, bInterfaceNr, 0);

        if (transfers.empty()) {
            return;
        }

        // wait for transfers to complete""",
    """        if (transfers.empty()) {
            libusb_set_interface_alt_setting(dev_handle->usb_handle, bInterfaceNr, 0);
            return;
        }

        // Wait for cancelled URBs to be reaped before changing the interface.
        // Switching alternate settings while URBs are outstanding can provoke
        // faults in some host controllers and makes disconnect diagnosis noisy.
        // wait for transfers to complete""",
    "stop ordering",
)
replace_unique(
    """        transfers.clear();
    }

    void uac_stream_handle_impl::set_sampling_rate""",
    """        transfers.clear();
        const int reset = libusb_set_interface_alt_setting(dev_handle->usb_handle, bInterfaceNr, 0);
        if (reset != LIBUSB_SUCCESS)
            LOG_WARN("Could not reset streaming interface: %s", libusb_error_name(reset));
    }

    void uac_stream_handle_impl::set_sampling_rate""",
    "stop reset after URB drain",
)
replace_unique(
    '            0 /* timeout */);',
    '            1000 /* timeout: do not hang indefinitely on failed USB device */);',
    "set_sampling_freq timeout",
)
source.write_text(text, encoding="utf-8")
print("Applied AB13X UAC1 altsetting-first, GET_INTERFACE and bounded EPIPE recovery patch")
