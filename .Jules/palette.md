## 2024-05-18 - First Insights
Learning: SDR Trunk utilizes Java Swing and MigLayout heavily for its interface. It integrates `jiconfont.swing.IconFontSwing` with `FontAwesome` for its icons. The application's UI is migrating to FlatLaf for a modernized look.
Action: Utilize `IconFontSwing` to upgrade legacy icons. Use `MigLayout` features for generous spacing. Take advantage of `FlatLaf` features if appropriate. Pay attention to keyboard accessibility on `JToggleButton` and other interactive elements.

## 2024-05-18 - Mute button icons
Learning: The `AudioPanel` uses legacy low-resolution raster graphics `audio_muted.png` and `audio_unmuted.png` for its mute button.
Action: Upgrade the Mute button to use vector icons via `IconFontSwing.buildIcon` with `FontAwesome.VOLUME_OFF` and `FontAwesome.VOLUME_UP` to improve high-dpi rendering and Apple-style visual crispness.

## 2024-05-18 - AudioPanel Mute Button Icons Refinement
Learning: Using static variables for icons created via `IconFontSwing` breaks dark mode and dynamic theming, because `IconFontSwing.buildIcon` without an explicit color defaults to black, which is hardcoded at class-load time.
Action: To support FlatLaf dynamic themes, update icons dynamically using `UIManager.getColor("Label.foreground")` when creating vector icons for UI components.
