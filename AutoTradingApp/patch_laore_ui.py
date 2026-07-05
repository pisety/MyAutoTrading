import re

with open("app/src/main/java/com/my/autotradingapp/MainActivity.kt", "r", encoding="utf-8") as f:
    content = f.read()

# 1. Update onSave callback in LaoreTabContent
target1 = """            onSave = { symbol, name, budget, split, yield ->
                if (name.isNotEmpty() && name != symbol) {
                    viewModel.updateStockName(symbol, name)
                }
                viewModel.saveLaoreSetting(symbol, com.my.autotradingapp.data.LaoreSettings(
                    enabled = true,
                    total_budget = budget,
                    split_count = split,
                    target_yield_percent = yield
                ))
                showSetupDialog = false
            }"""

replacement1 = """            onSave = { symbol, name, budget, split, yield ->
                if (name.isNotEmpty() && name != symbol) {
                    viewModel.updateStockName(symbol, name)
                }
                
                val existingSets = settingsMap.keys.filter { it.endsWith("_$symbol") }
                var maxSetId = 0
                for (k in existingSets) {
                    val id = k.substringBefore("_").toIntOrNull() ?: 1
                    if (id > maxSetId) maxSetId = id
                }
                val nextSetId = maxSetId + 1
                val newCompositeKey = "${nextSetId}_${symbol}"

                viewModel.saveLaoreSetting(newCompositeKey, com.my.autotradingapp.data.LaoreSettings(
                    enabled = true,
                    total_budget = budget,
                    split_count = split,
                    target_yield_percent = yield
                ))
                showSetupDialog = false
            }"""

# 2. Update Card rendering
target2 = """        } else {
            items(settingsMap.toList()) { (symbol, setting) ->
                val status = statusMap[symbol] ?: com.my.autotradingapp.data.LaoreStatus()
                val name = suggestions[symbol] ?: symbol
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(text = "${name} (${symbol})", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Text(text = "상태: ${if (setting.enabled) "활성화" else "중지됨"}", color = if (setting.enabled) Color(0xFF1976D2) else Color.Gray)
                            }
                            Switch(
                                checked = setting.enabled,
                                onCheckedChange = { isChecked ->
                                    viewModel.saveLaoreSetting(symbol, setting.copy(enabled = isChecked))
                                }
                            )
                        }"""

replacement2 = """        } else {
            val sortedSettings = settingsMap.toList().sortedWith(compareBy({ it.first.substringAfter("_") }, { it.first.substringBefore("_").toIntOrNull() ?: 1 }))
            items(sortedSettings) { (compositeKey, setting) ->
                val parts = compositeKey.split("_", limit = 2)
                val setId = if (parts.size == 2) parts[0] else "1"
                val symbol = if (parts.size == 2) parts[1] else compositeKey
                
                val status = statusMap[compositeKey] ?: com.my.autotradingapp.data.LaoreStatus()
                val name = suggestions[symbol] ?: symbol
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(text = "[세트 $setId] ${name} (${symbol})", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Text(text = "상태: ${if (setting.enabled) "활성화" else "중지됨"}", color = if (setting.enabled) Color(0xFF1976D2) else Color.Gray)
                            }
                            Switch(
                                checked = setting.enabled,
                                onCheckedChange = { isChecked ->
                                    viewModel.saveLaoreSetting(compositeKey, setting.copy(enabled = isChecked))
                                }
                            )
                        }"""

# 3. Update removeLaoreSetting call
target3 = """                        Row(modifier = Modifier.fillMaxWidth().padding(top=8.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { viewModel.removeLaoreSetting(symbol) }) {
                                Text("삭제/종료", color = Color.Red)
                            }
                        }"""
replacement3 = """                        Row(modifier = Modifier.fillMaxWidth().padding(top=8.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { viewModel.removeLaoreSetting(compositeKey) }) {
                                Text("삭제/종료", color = Color.Red)
                            }
                        }"""

if target1 in content: content = content.replace(target1, replacement1)
else: print("Target1 not found")

if target2 in content: content = content.replace(target2, replacement2)
else: print("Target2 not found")

if target3 in content: content = content.replace(target3, replacement3)
else: print("Target3 not found")

with open("app/src/main/java/com/my/autotradingapp/MainActivity.kt", "w", encoding="utf-8") as f:
    f.write(content)
print("Patch applied to MainActivity.")
