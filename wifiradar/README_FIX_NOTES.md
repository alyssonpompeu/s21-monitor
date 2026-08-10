# Wi-Fi Hunter Radar 1.2

- Shows the currently connected Wi-Fi network immediately, even if Android returns an empty or throttled scan.
- Uses ACCESS_FINE_LOCATION as the core requirement for startScan/getScanResults, while still requesting NEARBY_WIFI_DEVICES on Android 13+.
- Adds clearer diagnostics for Wi-Fi state, Location service, Nearby permission and scan throttling.
- Shows Wi-Fi contacts inside the radar itself with SSID and RSSI.
- Keeps bearing honest: contact direction is only shown after a 360-degree RSSI sweep; no fake angle is assigned to scan-only networks.
