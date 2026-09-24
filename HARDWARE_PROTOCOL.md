# Glasses and watch protocol

Documented from the behavior implemented in Lingo by 7dollarbooks. Device addresses are not listed here.

## Glasses photo

The camera button saves a full JPEG on the glasses. Lingo does not press that button.

Bluetooth carries the connection and the import command. The photo bytes travel over Wi-Fi Direct, then HTTP.

- Import command: `02 01 04 01`
- The glasses notify an IPv4 address
- `media.config` lists files
- Lingo downloads only the newest JPEG

A separate Bluetooth command, `02 01 06`, asks for a small preview. This firmware does not use that preview as the photo Lingo reads. There is no still-photo resolution setting in the commands Lingo uses. HeyCyan’s resolution list is for video.

After the full JPEG is on the phone, Lingo scales a copy to a longest side of 1024 pixels and compresses it at JPEG quality 70 before sending it to Gemini. The saved photo stays full size.

## Watch

Lingo talks to the ST9 with the Moyoung layout while the Watch screen is open.

- Service: `0000feea-0000-1000-8000-00805f9b34fb`
- Steps, distance, and calories notify and read: `0000fee1-0000-1000-8000-00805f9b34fb`
- Commands write: `0000fee2-0000-1000-8000-00805f9b34fb`
- Measurement replies notify: `0000fee3-0000-1000-8000-00805f9b34fb`

Packets start with `FE EA`. Heart rate is command 109 with payload `{0}`. Blood pressure is command 105 with payload `{0, 0, 0}`. Steps and calories come from the 9-byte fee1 packet. Heart rate and blood pressure are not polled on a timer.
