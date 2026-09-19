## What this changes

<!-- One or two sentences. What is different, and why. -->

## How it was verified

<!-- This project measures rather than assumes: frame rates, pixel counts,
     timings off a real panel. Say what you checked and what it showed. If you
     have no panel, say so -- the simulated panel is a legitimate way to test
     everything except throughput. -->

## Checklist

- [ ] `./gradlew.bat assembleDebug` passes
- [ ] `led/IPixelHub.kt` is untouched, or the same change is going to the other panel apps
- [ ] Animations are functions of elapsed time, not frame count
- [ ] Nothing new runs on the driver's thread
