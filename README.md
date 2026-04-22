# sdrtrunk - Kennebec Version

Welcome to the Kennebec version of sdrtrunk—a modernized, cross-platform Java application engineered for decoding, monitoring, recording, and streaming trunked mobile and related radio protocols using Software Defined Radios (SDR).

This repository is a fork of https://github.com/actionpagezello/sdrtrunk, which is itself a fork of the original SDRTrunk application (https://github.com/DSheirer/sdrtrunk). The Kennebec version adds an extensive layer of new capabilities on top of the features introduced by the actionpagezello fork.

This version is explicitly designed for listening to public safety and other radio frequencies and streaming audio to various internet streaming services. It takes the robust decoding engine of sdrtrunk and pairs it with a highly refined, context-aware user experience.

**Prerelease Notice:** The current version is a prerelease pending the completion of unit and system testing.

## What is SDRTrunk?
SDRTrunk is a Java-based application that transforms a standard computer and compatible Software Defined Radio hardware, such as RTL-SDR, Airspy, or HackRF, into a powerful, multi-channel radio scanner. Unlike traditional hardware scanners that can only listen to one frequency at a time, SDRTrunk captures a wide swath of the radio spectrum simultaneously.

This wideband capture allows the software to monitor entire trunked radio systems, where conversations dynamically jump across multiple frequencies. SDRTrunk automatically tracks system control channels, decodes the digital or analog voice traffic, and pieces the conversations back together in real time. It supports a variety of common public safety and commercial radio protocols, including Project 25 (P25) Phase 1 and 2, DMR, LTR, and standard analog FM. By utilizing software-based digital signal processing, it provides an accessible and highly configurable way to monitor local radio traffic, manage talkgroups, and route the resulting audio to external internet streaming platforms.

## Why the Kennebec Version?
* **Streamlined Monitoring:** Focused on optimizing the flow of mission-critical audio and data to internet streaming platforms.
* **Modern Efficiency:** Built to reduce cognitive load on operators with deeply integrated contextual help, streamlined configuration, and a modernized interface.
* **In-App Knowledge Base:** Say goodbye to alt-tabbing to a wiki. An embedded, searchable technical documentation viewer brings the knowledge you need right to your fingertips.
* **Deep OS Integration:** Utilizing modern Java and JNA for advanced desktop integration (e.g., native backdrops and theme syncing).

## Features
* Comprehensive digital and analog trunking support (P25, DMR, etc.).
* Automated audio recording, streaming, and metadata tagging.
* Contextual DSP explanations and interactive configuration.
* Fully searchable in-app Help Viewer.
* Two Tone Detect functionality.
* Refreshed Ux/GUI with new icons.
* New Ux/GUI for reviewing logs and recorded audio files.
* Consolidated all settings into a single user preference area.
* SDR Tuner width auto calculating.
* New stream type for IamResponding (local computer only via UDP) using two tone detect.
* Optional Gemini AI integration (when enabled, AI can auto set channel filters, review logs, and monitor application performance).
* Ability to set allocated memory directly via the user preferences Ux/GUI.

## Screenshots

<p align="center">
<b>Refreshed GUI (Now Playing)</b><br>
<img src="images/nowplayingscreenshot.png" alt="SDRTrunk Kennebec - Now Playing" width="800">
<br><br>

<b>In-App Knowledge Base & Help Viewer</b><br>
<img src="images/HelpScreenshot.png" alt="SDRTrunk Kennebec - Help and Docs" width="800">
<br><br>

<b>Two Tone Detect Functionality</b><br>
<img src="images/TwoToneDetectScreenShot.png" alt="SDRTrunk Kennebec - Two Tone Detect" width="800">
<br><br>

<b>Audio Recordings Review</b><br>
<img src="images/AudioRecordingScreenshot.png" alt="SDRTrunk Kennebec - Audio Recordings" width="800">
<br><br>

<b>Consolidated User Preferences</b><br>
<img src="images/UserPreferencesScreenshot.png" alt="SDRTrunk Kennebec - User Preferences" width="800">
</p>

## Minimum System Requirements
* **Operating System:** Windows (64-bit), Linux (64-bit) or Mac (64-bit, 12.x or higher)
* **CPU:** 4-core
* **RAM:** 8GB or more (preferred). Depending on usage, 4GB may be sufficient.
* **Java:** Requires Java 23+ (automatically provisioned via Gradle Toolchains).
