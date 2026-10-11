#!/usr/bin/env python3
"""Apply reproducible AB13X isochronous-submit diagnostics to pinned nExtCamera/libuac.

The build clones libuac at the SHA pinned in both GitHub workflows.
Fail closed if upstream source no longer matches expected anchors.
"""
from pathlib import Path

source = Path("app/src/main/cpp/vendor/libuac/src/uac_streaming.cpp")
text = source.read_text(encoding="utf-8")
if "RADIO_USB_ISO_DIAGNOSTICS_V1" in text:
    raise SystemExit("libuac patch already applied")
include = '#include "uac_exceptions.h"'
assert text.count(include) == 1, "libuac include anchor changed"
text = text.replace(include, include + '\n#include <cerrno>\n#include <string>', 1)
start = "        mActiveTransfers = 0;\n        for (int i = 0; i < NUM_ISO_TRANSFERS; ++i) {"
end = "        active = true;"
assert text.count(start) == 1 and text.count(end) == 1, "libuac transfer anchors changed"
begin, finish = text.index(start), text.index(end, text.index(start))
original = text[begin:finish]
assert 'libusb_submit_transfer(transfer)' in original
assert 'throw std::runtime_error("No transfers submitted!")' in original
replacement = r'''        // RADIO_USB_ISO_DIAGNOSTICS_V1
        // Do not hide the real submit error behind "No transfers submitted".
        // errno is best-effort on USBFS; libusb's symbolic return is authoritative.
        mActiveTransfers = 0;
        int allocationFailures = 0;
        int submitFailures = 0;
        std::string transferFailures;
        for (int i = 0; i < NUM_ISO_TRANSFERS; ++i) {
            libusb_transfer* transfer = libusb_alloc_transfer(iso_packets);
            if (transfer == nullptr) {
                ++allocationFailures;
                continue;
            }
            uint8_t *buffer = new (std::nothrow) uint8_t[transfer_size];
            if (buffer == nullptr) {
                libusb_free_transfer(transfer);
                ++allocationFailures;
                continue;
            }
            memset(buffer, 0, transfer_size);

            libusb_fill_iso_transfer(transfer, dev_handle->usb_handle,
                    altsetting.endpoint.bEndpointAddress, buffer, transfer_size,
                    iso_packets, cb, this, 1000);
            libusb_set_iso_packet_lengths(transfer, wMaxPacketSize);
            errno = 0;
            errval = libusb_submit_transfer(transfer);
            const int savedErrno = errno;
            LOG_DEBUG("iso submit %d: %s (errno=%d)", i, libusb_error_name(errval), savedErrno);
            if (errval == LIBUSB_SUCCESS) {
                transfers.push_back(transfer);
                ++mActiveTransfers;
            } else {
                ++submitFailures;
                if (!transferFailures.empty()) transferFailures += "; ";
                transferFailures += "#" + std::to_string(i) + "=" +
                        libusb_error_name(errval) + "(errno=" +
                        std::to_string(savedErrno) + ")";
                libusb_free_transfer(transfer);
                delete[] buffer;
            }
        }

        if (transfers.empty()) {
            const std::string reason =
                "No ISO transfers submitted: interface=" + std::to_string(bInterfaceNr) +
                ", alt=" + std::to_string(altsetting.bAlternateSetting) +
                ", endpoint=" + std::to_string(altsetting.endpoint.bEndpointAddress) +
                ", wMaxPacketSize=" + std::to_string(wMaxPacketSize) +
                ", packetsPerTransfer=" + std::to_string(iso_packets) +
                ", submitted=0/" + std::to_string(NUM_ISO_TRANSFERS) +
                ", allocFailed=" + std::to_string(allocationFailures) +
                ", submitFailed=" + std::to_string(submitFailures) +
                ", errors=[" + transferFailures + "]";
            libusb_set_interface_alt_setting(dev_handle->usb_handle, bInterfaceNr, 0);
            throw std::runtime_error(reason);
        }

'''
text = text[:begin] + replacement + text[finish:]
source.write_text(text, encoding="utf-8")
print("Applied RADIO_USB_ISO_DIAGNOSTICS_V1 to pinned libuac")
