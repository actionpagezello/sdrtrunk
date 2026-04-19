import re

with open('./src/main/java/io/github/dsheirer/gui/preference/application/ApplicationPreferenceEditor.java', 'r') as f:
    lines = f.readlines()

new_lines = []
skip = False
for line in lines:
    if line.startswith("<<<<<<< jules/redesign-settings-interface"):
        continue
    if line.startswith("======="):
        skip = True
        continue
    if line.startswith(">>>>>>> master"):
        skip = False
        continue
    if not skip:
        new_lines.append(line)

with open('./src/main/java/io/github/dsheirer/gui/preference/application/ApplicationPreferenceEditor.java', 'w') as f:
    f.writelines(new_lines)
