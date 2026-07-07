# Survivability matrix (phase 0.2)

Status: pending corpus files and runs. Column meaning:
std = xmp dc:description visible, xmp = engram xmp properties, rec = binary
records intact (crc ok), mpf = MPF offsets still valid (Ultra HDR alive),
motion = Motion Photo still plays in Google Photos.

Verdicts per cell: ok / transformed / gone, notes inline.

| # | Path | std | xmp | rec | mpf | motion |
|---|------|-----|-----|-----|-----|--------|
| 1 | Control: local copy, Files app, Syncthing | | | | | |
| 2 | GPhotos original quality, web download | | | | | |
| 2b | GPhotos original quality, Takeout | | | | | |
| 3 | GPhotos storage saver, download | | | | | |
| 4 | GPhotos editor, crop, save | | | | | |
| 5 | Pixel Markup editor, save | | | | | |
| 6 | WhatsApp: as photo / as document | | | | | |
| 7 | Telegram: as photo / as file | | | | | |
| 8 | Signal: as photo | | | | | |
| 9 | Gmail attachment | | | | | |
| 10 | GPhotos behavior after in-place rewrite of a backed-up photo | | | | | |
| 11 | Motion Photo playback after our write | | | | | |

Landmine verdicts (phase0-plan.md section 0.5) get recorded below as they land:

1. Ultra HDR MPF repair:
2. Motion Photo coexistence:
3. GPhotos duplicate-on-rewrite:
4. Storage saver behavior:
5. Transactional write pattern crash test:
6. Standard caption fields displayed by GPhotos / Apple Photos:
7. Play Console closed-test rule (current):
8. GPhotos Library API restriction (current):
9. Screenshots bucket per OEM:
10. PNG chunk survival:
