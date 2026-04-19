import re

with open('./src/main/java/io/github/dsheirer/gui/preference/application/ApplicationPreferenceEditor.java', 'r') as f:
    content = f.read()

conflict_search = """<<<<<<< HEAD
            HBox autoStartRow = new HBox(10);
            autoStartRow.getStyleClass().add("preferences-card-row");
            autoStartRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            javafx.scene.layout.Region spacer2 = new javafx.scene.layout.Region();
            HBox.setHgrow(spacer2, Priority.ALWAYS);
=======
            mEditorPane.add(getMemoryLimitLabel(), 0, ++row, 2, 1);
            GridPane.setHalignment(getMemoryComboBox(), HPos.RIGHT);
            mEditorPane.add(getMemoryComboBox(), 0, ++row, 2, 1);
>>>>>>> origin/master"""
conflict_replace = """            HBox autoStartRow = new HBox(10);
            autoStartRow.getStyleClass().add("preferences-card-row");
            autoStartRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            javafx.scene.layout.Region spacer2 = new javafx.scene.layout.Region();
            HBox.setHgrow(spacer2, Priority.ALWAYS);"""

content = content.replace(conflict_search, conflict_replace)

with open('./src/main/java/io/github/dsheirer/gui/preference/application/ApplicationPreferenceEditor.java', 'w') as f:
    f.write(content)
