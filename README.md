# Frigate Viewer

A seamless Android client for Frigate NVR that intelligently switches between local and remote URLs based on your network connection.

![Frigate Viewer Screenshot 1](assets/images/screenshot1.png)  ![Frigate Viewer Screenshot 2](assets/images/screenshot2.png)

## Features

- **Network-Aware Switching**: Automatic URL selection based on WiFi network
- **Custom Network Lists**: Define which WiFi networks use your local URL
- **Flexible Connection Modes**: Auto, Internal-only, or External-only options
- **Optimized Video Playback**: Hardware-accelerated WebView with WebRTC support
- **Stability**: Automatic recovery from network changes
- **Internal SSL Support**: Self-signed certificates accepted for internal URLs only

## Permissions

- **Required**: Internet access, Network state monitoring
- **Network Detection**: 
  - Android 13+: NEARBY_WIFI_DEVICES
  - Earlier versions: ACCESS_FINE_LOCATION
- **Optional**: Camera and microphone (for WebRTC features)

## Requirements

- Android 13+ recommended
- Frigate NVR with both local and remote access configured

## Security & Privacy

- **Zero Storage**: No video or images stored on your device
- **Isolation**: Keeping tokens, cookies, etc., in the app's private DOM storage rather than the device's web browser.
- **Viewer Only**: All processing remains on your Frigate server
- **Direct Connection**: No cloud services or intermediaries
- **No Telemetry**: No analytics or usage tracking
- **Secure Access**: Authentication handled by your Frigate instance
- **Certificate Handling**: Self-signed certificates accepted for internal connections only, standard certificates required for external URLs

## License

MIT License
