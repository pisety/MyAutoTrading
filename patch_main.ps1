$content = Get-Content -Path 'D:\01.SW\03.Tool\01.AndroidStudio\AndroidStudioProjects\MyAutoTrading\AutoTradingApp\app\src\main\java\com\my\autotradingapp\MainActivity.kt' -Raw -Encoding UTF8

$replacement1 = "Text(text = currentPriceText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    if (command == "BUY" && status.avail_cash > 0 && currentPriceNum > 0) {
                        val maxQty = status.avail_cash / currentPriceNum
                        val qtyText = if (maxQty < 1.0) String.format(java.util.Locale.US, "%.4f", maxQty) else kotlin.math.floor(maxQty).toInt().toString()
                        Text(text = "주문 가능 수량: 약 $qtyText", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }"
$content = $content -replace 'Text\(text = currentPriceText, style = MaterialTheme\.typography\.bodyMedium, fontWeight = FontWeight\.Bold, color = MaterialTheme\.colorScheme\.primary\)', $replacement1

Set-Content -Path 'D:\01.SW\03.Tool\01.AndroidStudio\AndroidStudioProjects\MyAutoTrading\AutoTradingApp\app\src\main\java\com\my\autotradingapp\MainActivity.kt' -Value $content -Encoding UTF8
