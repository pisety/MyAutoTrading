import pandas as pd
import yfinance as yf
from datetime import datetime
import numpy as np

def run_model(E_val):
    df_soxx_daily = yf.download('SOXX', start='2019-01-01', end='2026-12-31', progress=False)
    if isinstance(df_soxx_daily.columns, pd.MultiIndex):
        df_soxx_daily.columns = df_soxx_daily.columns.droplevel(1)
    df_soxx_daily = df_soxx_daily[['Close']].rename(columns={'Close': 'SOXX_Close'})
    df_soxx_daily['MA200'] = df_soxx_daily['SOXX_Close'].rolling(window=200).mean()
    df_soxx_daily['Prev_Close'] = df_soxx_daily['SOXX_Close'].shift(1)
    df_soxx_daily['Prev_MA200'] = df_soxx_daily['MA200'].shift(1)
    df_soxx_daily['Is_Bull'] = df_soxx_daily['Prev_Close'] > df_soxx_daily['Prev_MA200']
    df_soxx_daily.index = df_soxx_daily.index.strftime('%Y-%m-%d')
    bull_dict = df_soxx_daily['Is_Bull'].to_dict()

    df_soxl = pd.read_csv('data/SOXL_1min_2020_2026.csv')
    df_soxl['timestamp'] = pd.to_datetime(df_soxl['timestamp'])
    df_soxl.set_index('timestamp', inplace=True)
    df_soxl = df_soxl[~df_soxl.index.duplicated(keep='first')]
    df_soxl = df_soxl[['close']].rename(columns={'close': 'SOXL'})

    df_soxs = pd.read_csv('data/SOXS_1min_2020_2026.csv')
    df_soxs['timestamp'] = pd.to_datetime(df_soxs['timestamp'])
    df_soxs.set_index('timestamp', inplace=True)
    df_soxs = df_soxs[~df_soxs.index.duplicated(keep='first')]
    df_soxs = df_soxs[['close']].rename(columns={'close': 'SOXS'})
    
    df = df_soxl.join(df_soxs, how='outer').ffill().tz_convert('America/New_York').sort_index().dropna()

    cash = 100000.0
    shares = 0.0
    avg_price = 0.0
    reference_equity = 100000.0
    active_ticker = None
    monthly_equity = {}
    
    state = {
        'SOXL': {'prev_close': None, 'today_close': None, 'is_down_E': False, 'is_up_8': False, 'b_3pct': False, 'b_4pct': False, 'b_ah_1pct': False, 'b_ah_2pct': False},
        'SOXS': {'prev_close': None, 'today_close': None, 'is_down_E': False, 'is_up_8': False, 'b_3pct': False, 'b_4pct': False, 'b_ah_1pct': False, 'b_ah_2pct': False}
    }
    
    current_date = None
    timestamps = df.index
    soxl_prices = df['SOXL'].values
    soxs_prices = df['SOXS'].values
    is_bull_market = True
    
    for i in range(len(timestamps)):
        ts = timestamps[i]
        prices = {'SOXL': soxl_prices[i], 'SOXS': soxs_prices[i]}
        date_str = ts.strftime('%Y-%m-%d')
        month_str = ts.strftime('%Y-%m')
        time_str = ts.strftime('%H:%M:%S')
            
        if current_date != date_str:
            current_date = date_str
            regime = bull_dict.get(date_str, True)
            is_bull_market = True if pd.isna(regime) else bool(regime)
            
            for sym in ['SOXL', 'SOXS']:
                state[sym]['b_3pct'] = False; state[sym]['b_4pct'] = False
                state[sym]['b_ah_1pct'] = False; state[sym]['b_ah_2pct'] = False
                state[sym]['today_close'] = None
                state[sym]['is_down_E'] = False; state[sym]['is_up_8'] = False

        active_price = soxl_prices[i] if active_ticker == 'SOXL' else (soxs_prices[i] if active_ticker == 'SOXS' else None)
        total_equity = cash + shares * active_price if active_ticker else cash
        monthly_equity[month_str] = total_equity
        
        if shares > 0 and total_equity < reference_equity * 0.95:
            cash += shares * active_price; shares = 0; avg_price = 0.0; active_ticker = None; continue
            
        if shares > 0 and active_price >= avg_price * 1.15:
            cash += shares * active_price; shares = 0; avg_price = 0.0; active_ticker = None; continue
            
        if time_str == '16:00:00':
            for sym in ['SOXL', 'SOXS']:
                state[sym]['today_close'] = prices[sym]
                if state[sym]['prev_close'] is not None:
                    if state[sym]['today_close'] <= state[sym]['prev_close'] * (1 - abs(E_val)/100.0): state[sym]['is_down_E'] = True
                    if state[sym]['today_close'] >= state[sym]['prev_close'] * 1.08: state[sym]['is_up_8'] = True
                state[sym]['prev_close'] = state[sym]['today_close']
                
        eval_order = [active_ticker] if active_ticker else ['SOXL', 'SOXS']
        for sym in eval_order:
            if active_ticker is not None and active_ticker != sym: continue
            
            p = prices[sym]
            s = state[sym]
            
            if sym == 'SOXL':
                intra_1, intra_2 = (0.98, 0.97) if is_bull_market else (0.97, 0.96)
            else:
                intra_1, intra_2 = (0.97, 0.96)
            
            if time_str <= '16:00:00':
                if s['prev_close'] is not None:
                    if p <= s['prev_close'] * intra_1 and not s['b_3pct']:
                        s['b_3pct'] = True
                        if active_ticker is None: active_ticker = sym
                        reference_equity = total_equity
                        pct = 0.05 if cash <= total_equity * 0.20 else 0.10
                        buy_amt = total_equity * pct
                        if cash >= buy_amt:
                            new_shares = buy_amt / p; avg_price = (avg_price * shares + p * new_shares) / (shares + new_shares)
                            shares += new_shares; cash -= buy_amt
                    if p <= s['prev_close'] * intra_2 and not s['b_4pct']:
                        s['b_4pct'] = True
                        if active_ticker is None: active_ticker = sym
                        reference_equity = total_equity
                        pct = 0.05 if cash <= total_equity * 0.20 else 0.10
                        buy_amt = total_equity * pct
                        if cash >= buy_amt:
                            new_shares = buy_amt / p; avg_price = (avg_price * shares + p * new_shares) / (shares + new_shares)
                            shares += new_shares; cash -= buy_amt
            elif time_str > '16:00:00' and s['today_close'] is not None:
                if s['is_down_E']:
                    if p <= s['today_close'] * 0.99 and not s['b_ah_1pct']:
                        s['b_ah_1pct'] = True
                        if active_ticker is None: active_ticker = sym
                        reference_equity = total_equity
                        buy_amt = total_equity * 0.20
                        if cash >= buy_amt:
                            new_shares = buy_amt / p; avg_price = (avg_price * shares + p * new_shares) / (shares + new_shares)
                            shares += new_shares; cash -= buy_amt
                    if p <= s['today_close'] * 0.98 and not s['b_ah_2pct']:
                        s['b_ah_2pct'] = True
                        if active_ticker is None: active_ticker = sym
                        reference_equity = total_equity
                        buy_amt = total_equity * 0.10
                        if cash >= buy_amt:
                            new_shares = buy_amt / p; avg_price = (avg_price * shares + p * new_shares) / (shares + new_shares)
                            shares += new_shares; cash -= buy_amt
                        
    return monthly_equity

def calc_annual(monthly_equity):
    report = [{'Month': m, 'Equity': eq} for m, eq in monthly_equity.items()]
    df_rep = pd.DataFrame(report)
    summary = df_rep[df_rep['Month'].str.endswith('-12') | (df_rep['Month'] == df_rep['Month'].iloc[-1])]
    
    annual_rets = {}
    prev_eq = 100000.0
    for idx, row in summary.iterrows():
        year = row['Month'][:4]
        eq = row['Equity']
        ann_ret = ((eq / prev_eq) - 1.0) * 100.0
        annual_rets[year] = ann_ret
        prev_eq = eq
    final_eq = summary['Equity'].iloc[-1]
    cum_ret = ((final_eq / 100000.0) - 1.0) * 100.0
    annual_rets['Cumulative'] = cum_ret
    return annual_rets

e_vals = [7.0, 7.5, 7.8, 8.0]
results = {}

for e in e_vals:
    print(f"Running E={e}...")
    results[e] = calc_annual(run_model(e))

print("\n--- Final Results (Without AH TP) ---")
header = f"{'Year':<10}"
for e in e_vals:
    header += f" | E={e}%{'':<6}"
print(header)
print("-" * 65)

years = ['2020', '2021', '2022', '2023', '2024', '2025', '2026', 'Cumulative']
for y in years:
    line = f"{y:<10}"
    for e in e_vals:
        val = results[e].get(y, 0)
        line += f" | {val:>10.2f}% "
    print(line)

