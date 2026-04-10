#!/bin/bash
for file in ./src/main/java/io/github/dsheirer/gui/playlist/channel/*ConfigurationEditor*.java; do
    sed -i 's/m\(.*\)Pane\.setExpanded(true);//g' "$file"
    sed -i 's/m\(.*\)Pane\.setExpanded(false);//g' "$file"
    sed -i 's/m\(.*\)Pane\.setAnimated(false);//g' "$file"
    sed -i 's/m\(.*\)Pane\.setCollapsible(false);//g' "$file"
    sed -i 's/m\(.*\)Pane\.setText("...");//g' "$file"
done
