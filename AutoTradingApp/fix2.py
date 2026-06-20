import re

def fix_file():
    with open('MainActivity.kt', 'r', encoding='utf-8') as f:
        lines = f.readlines()

    fixed_lines = []
    for line in lines:
        if 'brokerName == ' in line:
            if '援?궡' in line or '국내' in line:
                if '紐⑥쓽' in line or '모의' in line:
                    if '한국투자' in line or 'KIS' in line or '쒓뎅' in line:
                        line = re.sub(r'\"[^\"]*\"', '\"한국투자증권 모의 (국내주식)\"', line)
                    else:
                        line = re.sub(r'\"[^\"]*\"', '\"키움증권 모의 (국내주식)\"', line)
                else:
                    if '한국투자' in line or 'KIS' in line or '쒓뎅' in line:
                        line = re.sub(r'\"[^\"]*\"', '\"한국투자증권 실전 (국내주식)\"', line)
                    else:
                        line = re.sub(r'\"[^\"]*\"', '\"키움증권 실전 (국내주식)\"', line)
            else:
                if '紐⑥쓽' in line or '모의' in line:
                    if '한국투자' in line or 'KIS' in line or '쒓뎅' in line:
                        line = re.sub(r'\"[^\"]*\"', '\"한국투자증권 모의 (해외주식)\"', line)
                    else:
                        line = re.sub(r'\"[^\"]*\"', '\"키움증권 모의 (해외주식)\"', line)
                else:
                    if '한국투자' in line or 'KIS' in line or '쒓뎅' in line:
                        line = re.sub(r'\"[^\"]*\"', '\"한국투자증권 실전 (해외주식)\"', line)
                    else:
                        line = re.sub(r'\"[^\"]*\"', '\"키움증권 실전 (해외주식)\"', line)
        
        elif 'TopAppBar(' in line or 'Text("?듦린' in line:
            line = re.sub(r'Text\(\"[^\"]*\"', 'Text(\"앱키 설정\"', line)
        elif 'label = { Text("怨꾩쥖' in line or '12345678' in line:
            line = re.sub(r'Text\(\"[^\"]*\"', 'Text(\"계좌번호 (예: 12345678-01)\"', line)
        elif 'label = { Text("鍮꾨' in line:
            line = re.sub(r'Text\(\"[^\"]*\"', 'Text(\"비밀번호\"', line)
        elif 'Button(onClick = { viewModel.saveAuthData' in line:
            # The next line is Text("저장")
            pass
        elif 'Text("?€??)' in line or 'Text("저장")' in line or 'Text("?€??)' in line:
            line = re.sub(r'Text\([^\)]*\)', 'Text(\"저장\")', line)
        elif 'val statusText = ' in line:
            line = '                    val statusText = "[${brokerName} | ${if (isMock) \\"모의\\" else \\"실전\\"} | ${if (isOverseas) \\"해외주식\\" else \\"국내주식\\"}]"\n'
        elif 'Text(text = "珥??됯?' in line:
            line = '                    Text(text = "총 평가자산", style = MaterialTheme.typography.labelLarge)\n'
        elif 'Text(text = "?섏씡瑜?' in line:
            line = '                        Text(text = "수익률: ", style = MaterialTheme.typography.bodyMedium)\n'
        elif 'Text(text = "理쒖쥌' in line:
            line = '                    Text(text = "최종 갱신: ${status.last_updated}", style = MaterialTheme.typography.labelSmall)\n'
        elif 'Text("蹂댁쑀 醫낅ぉ' in line:
            line = '            Text("보유 종목 현황", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)\n'
        elif 'Text("蹂댁쑀 以묒씤' in line:
            line = '                Text("보유 중인 종목이 없습니다.", color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))\n'
        elif 'label = { Text(if (isOverseas)' in line:
            line = '                label = { Text(if (isOverseas) "해외 감시 종목 (예: AAPL, TSLA)" else "국내 감시 종목 (예: 005930, 035420)") },\n'
        elif 'Text("?ㅼ떆媛?' in line:
            line = '            Text("실시간 시세 및 주문", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)\n'
        elif 'Text("媛먯떆 以묒씤' in line:
            line = '                Text("감시 중인 종목이 없습니다.", color = Color.Gray)\n'
        elif 'Text(text = "?먮룞留ㅻℓ' in line:
            line = '                Text(text = "자동매매 엔진 가동", fontWeight = FontWeight.Bold)\n'
        elif 'Text("湲됰벑二??⑦?' in line:
            line = '                    Text("급등주 단타 감시 설정 (${if (settings.surge_trading.enabled) \\"ON\\" else \\"OFF\\"})")\n'
        elif 'Text("湲됰벑二?媛먯떆' in line:
            line = '                        Text("급등주 감시 현황", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)\n'
        elif 'Text("${cfg.time' in line:
            line = '                                Text("${cfg.time_window_seconds}초 / ${cfg.surge_percent_threshold}% / ${cfg.buy_quantity}주", fontWeight = FontWeight.Bold)\n'
        elif 'Text(text = "${stock.quantity}' in line:
            line = '                Text(text = "${stock.quantity}주", style = MaterialTheme.typography.bodySmall)\n'
        elif 'Text("?먯젅"' in line:
            line = '                    Text("손절", fontSize = 12.sp)\n'
        elif 'Text("留ㅼ닔"' in line:
            line = '                    Text("매수", color = Color.White)\n'
        elif 'Text("留ㅻ룄"' in line:
            line = '                    Text("매도", color = Color.White)\n'
        elif 'val priceStr =' in line and 'stock.current_price' in line:
            line = '                    val priceStr = if (isOverseas) "$${String.format(Locale.US, \\"%.2f\\", stock.current_price)}" else "₩${String.format(Locale.US, \\"%,.0f\\", stock.current_price)}"\n'
        elif 'val priceStr =' in line and 'price' in line:
            line = '                val priceStr = if (isOverseas) "$${String.format(Locale.US, \\"%.2f\\", price)}" else "₩${String.format(Locale.US, \\"%,.0f\\", price)}"\n'
        elif 'Text("湲곕뒫 ?꾩껜' in line:
            line = '                        Text("기능 전체 활성화", fontWeight = FontWeight.Bold)\n'
        elif 'Text("媛먯떆 以묒씤 醫낅ぉ???놁뒿?덈떎.\\n癒쇱?' in line:
            line = '                        Text("감시 중인 종목이 없습니다.\\n먼저 관심 종목을 추가하고 저장해주세요.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)\n'
        elif 'Text(if (isExpanded) "₩ else "₩' in line or 'Text(if (isExpanded) "?' in line:
            line = '                                        Text(if (isExpanded) "▼" else "▶", color = Color.Gray, fontSize = 12.sp)\n'
        elif 'label = { Text("媛먯떆 (珥?") }' in line:
            line = '                                                label = { Text("감시 (초)") },\n'
        elif 'label = { Text("湲됰벑 (%)") }' in line:
            line = '                                                label = { Text("급등 (%)") },\n'
        elif 'label = { Text("留ㅼ닔 ?섎웾 (0?€ 媛먯떆 ?쒖쇅)") }' in line:
            line = '                                            label = { Text("매수 수량 (0은 감시 제외)") },\n'
        elif 'Text("?ㅼ젙??' in line and '${time}' in line:
            line = '                                                Text("설정됨: ${time}초 / ${percent}% / ${qty}주", style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)\n'
        elif 'Text("?€湲곗쨷' in line and '${time}' in line:
            line = '                                                Text("대기중 (전체 활성화 OFF): ${time}초 / ${percent}% / ${qty}주", style = MaterialTheme.typography.bodySmall, color = Color.Gray)\n'
        elif 'Text("媛먯떆 ?쒖쇅' in line:
            line = '                                            Text("감시 제외 (수량 0)", style = MaterialTheme.typography.bodySmall, color = Color.Gray)\n'
        elif 'Toast.makeText(context, "湲됰벑二?' in line:
            line = '                    Toast.makeText(context, "급등주 감시 설정 완료", Toast.LENGTH_SHORT).show()\n'
        elif 'TextButton(onClick = { showSurgeDialog = false }) { Text("痍⑥냼") }' in line:
            line = '                TextButton(onClick = { showSurgeDialog = false }) { Text("취소") }\n'
        elif 'Text("?쒖옣媛€(Market)"' in line:
            line = '                                    Text("시장가(Market)")\n'
        elif 'Text("吏€?뺢?(Limit)"' in line:
            line = '                                    Text("지정가(Limit)")\n'
        elif 'Text("二쇰Ц ?섎웾"' in line:
            line = '                        Text("주문 수량")\n'
        elif 'Text("二쇰Ц 媛€寃?(吏€?뺢€?寃쎌슦)"' in line:
            line = '                        Text("주문 가격(지정가일 경우)")\n'
        elif 'Text(if (orderCommand == "BUY") "留ㅼ닔二쇰Ц" else "留ㅻ룄二쇰Ц"' in line:
            line = '                    Text(if (orderCommand == "BUY") "매수주문" else "매도주문")\n'
        elif 'Text("?먯젅 媛€寃?"' in line:
            line = '                        Text("손절 가격")\n'
        elif 'Text("?먯젅 媛€寃⑺븯?앹떆 留ㅻ룄"' in line:
            line = '                    Text("손절 가격하락시 매도")\n'
            
        fixed_lines.append(line)

    with open('MainActivity_fixed2.kt', 'w', encoding='utf-8') as f:
        f.writelines(fixed_lines)

if __name__ == '__main__':
    fix_file()
