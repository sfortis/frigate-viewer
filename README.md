# Frigate Viewer

A network-aware Android client for Frigate NVR that automatically switches between internal and external URLs based on your WiFi connection.

## Features

- **Automatic URL switching**: Uses internal URL on home WiFi, external URL elsewhere
- **Manual override**: Force internal or external URL when needed
- **Full WebView support**: JavaScript, zoom, and navigation
- **Keep screen on**: Prevents screen timeout while viewing cameras

## Quick Start

1. Install and open the app
2. Grant WiFi detection permissions
3. In Settings, configure:
   - Internal URL (e.g., `http://192.168.1.100:5000`)
   - External URL (e.g., `https://example.com/frigate`)
   - Add your home WiFi networks
4. The app automatically loads the correct URL based on your connection

## Requirements

- Android 12+ (API 31)
- Frigate NVR accessible locally and/or remotely

## Permissions

- Internet and network state access
- WiFi detection (location permission on older Android versions)

Built with Kotlin and Material Design.