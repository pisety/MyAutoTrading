import re

with open('app/src/main/java/com/my/autotradingapp/ui/components/WatchlistManagementDialog.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# Remove imports
content = re.sub(r'import androidx\.compose\.material\.icons\.Icons\n', '', content)
content = re.sub(r'import androidx\.compose\.material\.icons\.filled\.ArrowDownward\n', '', content)
content = re.sub(r'import androidx\.compose\.material\.icons\.filled\.ArrowUpward\n', '', content)
content = re.sub(r'import androidx\.compose\.material\.icons\.filled\.Delete\n', '', content)

# Replace Icons with Text
content = content.replace('Icon(Icons.Default.ArrowUpward, contentDescription = "위로", tint = if (index > 0) Color.DarkGray else Color.LightGray)', 'Text("▲", color = if (index > 0) Color.DarkGray else Color.LightGray)')
content = content.replace('Icon(Icons.Default.ArrowDownward, contentDescription = "아래로", tint = if (index < managedList.size - 1) Color.DarkGray else Color.LightGray)', 'Text("▼", color = if (index < managedList.size - 1) Color.DarkGray else Color.LightGray)')
content = content.replace('Icon(Icons.Default.Delete, contentDescription = "삭제", tint = Color.Red)', 'Text("❌", color = Color.Red)')

with open('app/src/main/java/com/my/autotradingapp/ui/components/WatchlistManagementDialog.kt', 'w', encoding='utf-8') as f:
    f.write(content)
print("Icons replaced with Text successfully.")
