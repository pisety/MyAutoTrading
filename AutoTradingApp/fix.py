import re

with open('MainActivity.kt', 'r', encoding='utf-8') as f:
    text = f.read()

replacements = {
    '\"?쒓뎅?ъ옄利앷텒 ?ㅼ쟾 (援?궡二쇱떇)\"': '\"한국투자증권 실전 (국내주식)\"',
    '\"?쒓뎅?ъ옄利앷텒 紐⑥쓽 (援?궡二쇱떇)\"': '\"한국투자증권 모의 (국내주식)\"',
    '\"?쒓뎅?ъ옄利앷텒 紐⑥쓽 (?댁쇅二쇱떇)\"': '\"한국투자증권 모의 (해외주식)\"',
    '\"?쒓뎅?ъ옄利앷텒 ?ㅼ쟾 (?댁쇅二쇱떇)\"': '\"한국투자증권 실전 (해외주식)\"',
    '\"?ㅼ利앷텒 ?ㅼ쟾 (援?궡二쇱떇)\"': '\"키움증권 실전 (국내주식)\"',
    '\"?ㅼ利앷텒 紐⑥쓽 (援?궡二쇱떇)\"': '\"키움증권 모의 (국내주식)\"',
    '\"?ㅼ利앷텒 ?ㅼ쟾 (?댁쇅二쇱떇)\"': '\"키움증권 실전 (해외주식)\"',
    '\"?ㅼ利앷텒 紐⑥쓽 (?댁쇅二쇱떇)\"': '\"키움증권 모의 (해외주식)\"',
    '\"?듦린 ?ㅼ젙\"': '\"앱키 설정\"',
    '\"怨꾩쥖踰덊샇 (?? 12345678-01)\"': '\"계좌번호 (예: 12345678-01)\"',
    '\"鍮꾨踰덊샇\"': '\"비밀번호\"',
    '\"?€??)': '\"저장\")',
    '\"[${brokerName} | ${if (isMock) \\"紐⑥쓽\\" else \\"?ㅼ쟾\\"} | ${if (isOverseas) \\"?댁쇅二쇱떇\\" else \\"援?궡二쇱떇\\"}]\"': '\"[${brokerName} | ${if (isMock) \\"모의\\" else \\"실전\\"} | ${if (isOverseas) \\"해외주식\\" else \\"국내주식\\"}]\"',
    '\"珥??됯??먯궛\"': '\"총 평가자산\"',
    '\"?섏씡瑜? \"': '\"수익률: \"',
    '\"理쒖쥌 媛깆떊: ${status.last_updated}\"': '\"최종 갱신: ${status.last_updated}\"',
    '\"蹂댁쑀 醫낅ぉ ?꾪솴\"': '\"보유 종목 현황\"',
    '\"蹂댁쑀 以묒씤 醫낅ぉ???놁뒿?덈떎.\"': '\"보유 중인 종목이 없습니다.\"',
    '\"?댁쇅 媛먯떆 醫낅ぉ (?? AAPL, TSLA)\"': '\"해외 감시 종목 (예: AAPL, TSLA)\"',
    '\"援?궡 媛먯떆 醫낅ぉ (?? 005930, 035420)\"': '\"국내 감시 종목 (예: 005930, 035420)\"',
    '\"?ㅼ떆媛??쒖꽭 諛?二쇰Ц\"': '\"실시간 시세 및 주문\"',
    '\"媛먯떆 以묒씤 醫낅ぉ???놁뒿?덈떎.\"': '\"감시 중인 종목이 없습니다.\"',
    '\"?먮룞留ㅻℓ ?붿쭊 媛€??': '\"자동매매 엔진 가동\",',
    '\"湲됰벑二??⑦? 媛먯떆 ?ㅼ젙 (${if (settings.surge_trading.enabled) \\"ON\\" else \\"OFF\\"})\"': '\"급등주 단타 감시 설정 (${if (settings.surge_trading.enabled) \\"ON\\" else \\"OFF\\"})\"',
    '\"湲됰벑二?媛먯떆 ?꾪솴\"': '\"급등주 감시 현황\"',
    '二?,': '주\",',
    '二?\"': '주\"',
    '珥?/': '초 /',
    '\"?먯젅\"': '\"손절\"',
    '\"留ㅼ닔\"': '\"매수\"',
    '\"留ㅻ룄\"': '\"매도\"',
    '\"??': '\"₩',
    '\"??{': '\"₩${',
    '\"?ㅼ쟾\"': '\"실전\"',
    '\"紐⑥쓽\"': '\"모의\"',
    '\"?댁쇅二쇱떇\"': '\"해외주식\"',
    '\"援?궡二쇱떇\"': '\"국내주식\"',
    'Text("?€??)': 'Text("저장")',
    '\"[${brokerName} | ${if (isMock) \"紐⑥쓽\" else \"?ㅼ쟾\"} | ${if (isOverseas) \"?댁쇅二쇱떇\" else \"援?궡二쇱떇\"}]\"': '\"[${brokerName} | ${if (isMock) \\"모의\\" else \\"실전\\"} | ${if (isOverseas) \\"해외주식\\" else \\"국내주식\\"}]\"',
    'Text(text = \"?섏씡瑜? \",': 'Text(text = \"수익률: \",',
    'Text(text = \"理쒖쥌 媛깆떊: ${status.last_updated}\",': 'Text(text = \"최종 갱신: ${status.last_updated}\",',
    'Text(\"蹂댁쑀 醫낅ぉ ?꾪솴\",': 'Text(\"보유 종목 현황\",',
    'Text(\"蹂댁쑀 以묒씤 醫낅ぉ???놁뒿?덈떎.\",': 'Text(\"보유 중인 종목이 없습니다.\",',
    'Text(if (isOverseas) \"?댁쇅 媛먯떆 醫낅ぉ (?? AAPL, TSLA)\" else \"援?궡 媛먯떆 醫낅ぉ (?? 005930, 035420)\")': 'Text(if (isOverseas) \"해외 감시 종목 (예: AAPL, TSLA)\" else \"국내 감시 종목 (예: 005930, 035420)\")',
    'Text(\"?ㅼ떆媛??쒖꽭 諛?二쇰Ц\",': 'Text(\"실시간 시세 및 주문\",',
    'Text(\"媛먯떆 以묒씤 醫낅ぉ???놁뒿?덈떎.\",': 'Text(\"감시 중인 종목이 없습니다.\",',
    'Text(text = \"?먮룞留ㅻℓ ?붿쭊 媛€??,': 'Text(text = \"자동매매 엔진 가동\",',
    'Text(\"湲됰벑二??⑦? 媛먯떆 ?ㅼ젙 (${if (settings.surge_trading.enabled) \"ON\" else \"OFF\"})\")': 'Text(\"급등주 단타 감시 설정 (${if (settings.surge_trading.enabled) \\"ON\\" else \\"OFF\\"})\")',
    'Text(\"湲됰벑二?媛먯떆 ?꾪솴\",': 'Text(\"급등주 감시 현황\",',
    'Text(\"${cfg.time_window_seconds}珥?/ ${cfg.surge_percent_threshold}% / ${cfg.buy_quantity}二?,': 'Text(\"${cfg.time_window_seconds}초 / ${cfg.surge_percent_threshold}% / ${cfg.buy_quantity}주\",',
    'Text(text = \"${stock.quantity}二?,': 'Text(text = \"${stock.quantity}주\",',
    '\"??{String.format(Locale.US, \"%,.0f\", stock.current_price)}\"': '\"₩${String.format(Locale.US, \\"%,.0f\\", stock.current_price)}\"',
    'Text(\"?먯젅\",': 'Text(\"손절\",',
    '\"??{String.format(Locale.US, \"%,.0f\", price)}\"': '\"₩${String.format(Locale.US, \\"%,.0f\\", price)}\"',
    'Text(\"留ㅼ닔\",': 'Text(\"매수\",',
    'Text(\"留ㅻ룄\",': 'Text(\"매도\",',
}

for k, v in replacements.items():
    text = text.replace(k, v)

with open('MainActivity_fixed.kt', 'w', encoding='utf-8') as f:
    f.write(text)
