# Telegram Call Notifier

A simple Android application that forwards incoming call notifications to a Telegram channel.

## Features
- Detects incoming calls (Ringing state).
- Sends notification to Telegram with Caller Number and Time.
- Reports Battery Level and Network Type.
- Sends "Call Ended" notification with duration.
- Heartbeat message every 15 minutes.
- Works in background (Foreground Service).
- Auto-start on device boot.

## Setup

1. **Telegram Bot:**
   - Create a bot via [@BotFather](https://t.me/BotFather).
   - Get the **Bot Token**.
   - Create a channel and add the bot as Admin.
   - Get the **Chat ID** (e.g., using `@userinfobot` or API).

2. **App Configuration:**
   - Install the APK.
   - Open the app.
   - Enter **Bot Token** and **Chat ID** in the fields.
   - Click **Save & Start Service**.

## Build
This project uses GitHub Actions to build the APK automatically.
Go to the **Actions** tab in your GitHub repository to download the latest APK.
