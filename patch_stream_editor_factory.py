import re

with open('src/main/java/io/github/dsheirer/gui/playlist/streaming/StreamEditorFactory.java', 'r') as f:
    content = f.read()

content = content.replace('import io.github.dsheirer.audio.broadcast.BroadcastServerType;', 'import io.github.dsheirer.audio.broadcast.BroadcastServerType;\nimport io.github.dsheirer.gui.playlist.streaming.IAmRespondingEditor;')

get_editor_pattern = r'(case ZELLO:\s+return new ZelloConsumerEditor\(playlistManager\);)'
content = re.sub(get_editor_pattern, r'\1\n            case IAMRESPONDING:\n                return new IAmRespondingEditor(playlistManager);', content)

with open('src/main/java/io/github/dsheirer/gui/playlist/streaming/StreamEditorFactory.java', 'w') as f:
    f.write(content)
