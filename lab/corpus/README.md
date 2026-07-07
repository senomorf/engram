# Phase 0 corpus

Real files from real devices. Drop them here with these names (add more freely,
these are the minimum for the survivability matrix):

- pixel9-ultrahdr.jpg   default Pixel 9 camera photo (Ultra HDR is default-on)
- pixel9-motion.jpg     Pixel 9 Motion Photo (shows the film icon in Google Photos)
- pixel9-screenshot.png any screenshot
- pixel9-video.mp4      short camera video, a few seconds is enough
- <device>-photo.jpg and <device>-screenshot.* from each family/friend device model

Pulling from the phone over USB:

    adb shell ls -t /sdcard/DCIM/Camera | head
    adb pull "/sdcard/DCIM/Camera/<name>" lab/corpus/pixel9-ultrahdr.jpg
    adb shell ls -t /sdcard/Pictures/Screenshots | head
    adb pull "/sdcard/Pictures/Screenshots/<name>" lab/corpus/pixel9-screenshot.png

Nothing in this directory is committed (see .gitignore): family photos stay off
the repo. The harness reads from here; results go into ../survivability-matrix.md.
