# Tone Detector Tool — Project Brief

## Goal

Build a standalone Python command-line tool that batch-processes WAV audio recordings from SDRTrunk and automatically detects paging tones (two-tone sequential, single tone, DTMF, 5-tone). The output is a CSV file with detected tone information. This is a one-time data capture exercise — the user needs to identify tone-out assignments for fire/EMS departments to configure ThinLine Radio alerting.

## Background

- The user runs **SDRTrunk** (a Java-based P25/NBFM trunking decoder) across multiple computers monitoring Massachusetts public safety radio
- SDRTrunk records audio as WAV files (8 kHz sample rate, 16-bit PCM mono) for each call/transmission
- Many NBFM fire/EMS channels use **two-tone sequential paging** to dispatch specific units (e.g., Engine 1 = 1050.0 Hz / 1130.0 Hz)
- The user needs to identify the tone frequencies and durations for each unit to configure **ThinLine Radio** (a cloud-based radio streaming/alerting service)
- Manually listening to each recording is impractical — hundreds of files per day across multiple channels

## Requirements

### Input
- Directory of WAV files from SDRTrunk recordings
- WAV format: 8 kHz sample rate, 16-bit PCM, mono (standard SDRTrunk output)
- Files may or may not contain tones — most will be voice-only

### Detection — Priority Order
1. **Two-tone sequential** (highest priority) — Classic fire/EMS paging: Tone A followed by Tone B, each typically 300-3000 Hz, duration 0.5-3.0 seconds each
2. **Single tone** — Long single-frequency tone, typically 1-8 seconds
3. **DTMF** — Standard 4x4 DTMF grid tones
4. **5-tone** — Sequential 5-tone paging (lower priority)

### Output — CSV File
Each row represents a detected tone event:

| Column | Description |
|--------|-------------|
| Filename | Source WAV file name |
| Timestamp | Date/time from file metadata or filename |
| Tone_Type | "two-tone", "single", "DTMF", "5-tone" |
| Tone_A_Hz | First tone frequency (Hz) |
| Tone_A_Duration_ms | First tone duration (milliseconds) |
| Tone_B_Hz | Second tone frequency (Hz, blank for single tone) |
| Tone_B_Duration_ms | Second tone duration (ms, blank for single tone) |
| Channel | Channel name (parsed from SDRTrunk filename if available) |
| Confidence | Detection confidence score (0-100%) |

### Detection Algorithm
- Use **Goertzel algorithm** for efficient single-frequency detection (more efficient than FFT for detecting specific frequencies)
- Process audio in short frames (10-20 ms windows)
- Detect tone onset/offset by energy threshold in the target frequency band
- For two-tone: detect Tone A, then detect Tone B starting within a short gap window
- Frequency resolution: ±2 Hz accuracy
- Minimum tone duration threshold: 200 ms (reject brief transients)
- Report all detected tone events, even if no lookup match

### Optional: Tone Lookup Matching
- Accept an optional CSV lookup table mapping known tone pairs to unit names
- Format: `Name,ToneA_Hz,ToneB_Hz,Tolerance_Hz`
- Example: `Engine 1,1050.0,1130.0,2.0`
- If a detected tone pair matches a lookup entry (within tolerance), add the matched name to the output CSV

### Usage
```
python tone_detector.py /path/to/recordings/ --output tones.csv
python tone_detector.py /path/to/recordings/ --output tones.csv --lookup tone_lookup.csv
python tone_detector.py /path/to/recordings/ --output tones.csv --min-duration 300 --tolerance 2.0
```

## Technical Notes

- SDRTrunk WAV filenames typically contain the channel name and timestamp
- The tool should handle large batches (hundreds of files) efficiently
- Consider using numpy for array operations and scipy for signal processing if needed
- The Goertzel algorithm is preferred over FFT because we're looking for specific frequency ranges, not a full spectrum
- Two-tone sequential paging typically has a brief gap (10-50 ms) between Tone A and Tone B
- Some systems use "group tone" where Tone A is the same for all units in a station and Tone B identifies the specific unit

## Reference

The user uploaded `2ToneDetector.exe` — a Windows two-tone detector utility — as a reference for the type of detection needed. The Python tool should provide similar functionality but with batch processing and CSV output.
