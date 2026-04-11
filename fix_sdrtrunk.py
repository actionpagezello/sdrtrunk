import re

with open('./src/main/java/io/github/dsheirer/gui/SDRTrunk.java', 'r') as f:
    content = f.read()

# Make sure we didn't screw up the brackets for the menu item
content = re.sub(r'public class BroadcastStatusVisibleMenuItem\s*{\s*}', '', content)

# Clean up view menu
content = re.sub(r'viewMenu\.getItems\(\)\.add\(new BroadcastStatusVisibleMenuItem\(\)\);', '', content)

with open('./src/main/java/io/github/dsheirer/gui/SDRTrunk.java', 'w') as f:
    f.write(content)
