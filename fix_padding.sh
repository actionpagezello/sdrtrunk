#!/bin/bash
file="./src/main/java/io/github/dsheirer/gui/playlist/channel/ChannelConfigurationEditor.java"

# Increase general padding and VGap/HGap in mTextFieldPane to look more spacious.
sed -i 's/mTextFieldPane.setVgap(10);/mTextFieldPane.setVgap(15);/g' "$file"
sed -i 's/mTextFieldPane.setHgap(10);/mTextFieldPane.setHgap(20);/g' "$file"
sed -i 's/setPadding(new Insets(10, 10, 10, 10));/setPadding(new Insets(20, 20, 20, 20));/g' "$file"
