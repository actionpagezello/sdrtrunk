import re

with open('./src/main/java/io/github/dsheirer/gui/preference/application/ApplicationPreferenceEditor.java', 'r') as f:
    content = f.read()

# Replace the conflict markers with just the modern HEAD styling but adapting the MemoryComboBox logic from master
conflict_search = """<<<<<<< HEAD
            mEditorPane.getChildren().addAll(diagCard, autoStartCard, memoryCard);
=======
            Separator separator2 = new Separator(Orientation.HORIZONTAL);
            GridPane.setHgrow(separator2, Priority.ALWAYS);
            mEditorPane.add(separator2, 0, ++row, 3, 1);

            mEditorPane.add(getMemoryLimitLabel(), 0, ++row, 2, 1);
            GridPane.setHalignment(getMemoryComboBox(), HPos.RIGHT);
            mEditorPane.add(getMemoryComboBox(), 0, ++row, 2, 1);

            GridPane.setHalignment(getMemoryWarningLabel(), HPos.RIGHT);
            mEditorPane.add(getMemoryWarningLabel(), 0, ++row, 3, 1);

            ColumnConstraints c1 = new ColumnConstraints();
            c1.setPercentWidth(30);
            ColumnConstraints c2 = new ColumnConstraints();
            c2.setHgrow(Priority.ALWAYS);
            mEditorPane.getColumnConstraints().addAll(c1, c2);
>>>>>>> origin/master"""
conflict_replace = """            mEditorPane.getChildren().addAll(diagCard, autoStartCard, memoryCard);"""

# Replace the Spinner reference in the Card 3 with the ComboBox from master
mem_search = """            HBox memSpinnerBox = new HBox(5);
            memSpinnerBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            memSpinnerBox.getChildren().addAll(getMemorySpinner(), new Label("GB"));

            memoryRow.getChildren().addAll(getMemoryLimitLabel(), spacer3, memSpinnerBox);"""
mem_replace = """            memoryRow.getChildren().addAll(getMemoryLimitLabel(), spacer3, getMemoryComboBox());"""

content = content.replace(conflict_search, conflict_replace)
content = content.replace(mem_search, mem_replace)
content = content.replace("private Spinner<Integer> mMemorySpinner;", "private ComboBox<MemoryOption> mMemoryComboBox;")
content = content.replace("import javafx.scene.control.Spinner;", "import javafx.scene.control.Spinner;\nimport javafx.scene.control.ComboBox;\nimport java.lang.management.ManagementFactory;\nimport java.util.ArrayList;\nimport java.util.List;\nimport javafx.scene.layout.Region;")


# Remove getMemorySpinner from the file since it's replaced by getMemoryComboBox
spinner_search = """    private Spinner<Integer> getMemorySpinner()
    {
        if(mMemorySpinner == null)
        {
            mMemorySpinner = new Spinner<>(1, 64, mApplicationPreference.getAllocatedMemory(), 1);
            mMemorySpinner.valueProperty().addListener((observable, oldValue, newValue) -> mApplicationPreference.setAllocatedMemory(newValue));
        }

        return mMemorySpinner;
    }"""
content = content.replace(spinner_search, "")

with open('./src/main/java/io/github/dsheirer/gui/preference/application/ApplicationPreferenceEditor.java', 'w') as f:
    f.write(content)
