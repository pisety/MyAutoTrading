import pandas as pd
import yfinance as yf
from datetime import datetime
import numpy as np

def run_model_d_soxx_ma():
    print("Downloading SOXX daily data...")
    # Download SOXX daily data to calculate MA200
    # Start from 2019 to have enough data for 2020 MA200
    df_soxx_daily = yf.download('SOXX', start='2019-01-01', end='2026-12-31', progress=False)
    
    # Check if we got a MultiIndex (happens with newer yfinance)
    if isinstance(df_soxx_daily.columns, pd.MultiIndex):
        df_soxx_daily.columns = df_soxx_daily.columns.droplevel(1)
        
    df_soxx_daily = df_soxx_daily[['Close']].rename(columns={'Close': 'SOXX_Close'})
    df_soxx_daily['MA200'] = df_soxx_daily['SOXX_Close'].rolling(window=200).mean()
    # Shift MA200 by 1 day so that we use yesterday's MA200 for today's trading
    df_soxx_daily['MA200_prev'] = df_soxx_daily['MA200'].shift(1)
    
    # We only need the MA200_prev for our dates. Make index timezone-aware to match 1min data if needed,
    # but since it's just dates, we can convert dates to strings for easy lookup.
    df_soxx_daily.index = df_soxx_daily.index.strftime('%Y-%m-%d')
    ma200_dict = df_soxx_daily['MA200_prev'].dropna().to_dict()

    print("Loading 1-min data...")
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
    trade_counts = {}
    
    state = {
        'SOXL': {'prev_close': None, 'today_close': None, 'is_down_E': False, 'is_up_8': False, 'b_3pct': False, 'b_4pct': False, 'b_ah_1pct': False, 'b_ah_2pct': False, 's_ah_1': False, 's_ah_2': False, 's_ah_3': False, 's_ah_4': False},
        'SOXS': {'prev_close': None, 'today_close': None, 'is_down_E': False, 'is_up_8': False, 'b_3pct': False, 'b_4pct': False, 'b_ah_1pct': False, 'b_ah_2pct': False, 's_ah_1': False, 's_ah_2': False, 's_ah_3': False, 's_ah_4': False}
    }
    
    current_date = None
    timestamps = df.index
    soxl_prices = df['SOXL'].values
    soxs_prices = df['SOXS'].values
    
    current_ma200 = None
    soxx_today_close = None
    
    print("Running simulation...")
    for i in range(len(timestamps)):
        ts = timestamps[i]
        prices = {'SOXL': soxl_prices[i], 'SOXS': soxs_prices[i]}
        date_str = ts.strftime('%Y-%m-%d')
        month_str = ts.strftime('%Y-%m')
        year_str = ts.strftime('%Y')
        time_str = ts.strftime('%H:%M:%S')
        
        if year_str not in trade_counts:
            trade_counts[year_str] = {'SOXL': 0, 'SOXS': 0}
            
        if current_date != date_str:
            current_date = date_str
            # Use SOXX's MA200 from yesterday for today's market regime
            current_ma200 = ma200_dict.get(date_str, None)
            
            for sym in ['SOXL', 'SOXS']:
                state[sym]['b_3pct'] = False; state[sym]['b_4pct'] = False
                state[sym]['b_ah_1pct'] = False; state[sym]['b_ah_2pct'] = False
                state[sym]['s_ah_1'] = False; state[sym]['s_ah_2'] = False
                state[sym]['s_ah_3'] = False; state[sym]['s_ah_4'] = False
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
                    E_val = -7.2
                    if state[sym]['today_close'] <= state[sym]['prev_close'] * (1 + E_val/100.0): state[sym]['is_down_E'] = True
                    if state[sym]['today_close'] >= state[sym]['prev_close'] * 1.08: state[sym]['is_up_8'] = True
                state[sym]['prev_close'] = state[sym]['today_close']
                
        eval_order = [active_ticker] if active_ticker else ['SOXL', 'SOXS']
        for sym in eval_order:
            if active_ticker is not None and active_ticker != sym: continue
            
            p = prices[sym]
            s = state[sym]
            
            # Regime switching using SOXX MA200
            # To know if we are in a bull market today, we compare yesterday's SOXX close 
            # to yesterday's MA200. Let's approximate by saying if today's SOXX price is needed, 
            # we can just use the MA200 check we prepared.
            # wait, the simplest is: Is yesterday's SOXX close > yesterday's MA200?
            # actually ma200_dict already contains the MA200 from yesterday.
            # Let's get yesterday's SOXX close. 
            is_bull_market = True
            if current_ma200 is not None:
                # We need yesterday's SOXX close. We can get it from df_soxx_daily.
                try:
                    yesterday_close = df_soxx_daily.loc[date_str, 'SOXX_Close']
                    # wait, df_soxx_daily.loc[date_str] is TODAY's close.
                    # We need to shift it too.
                except:
                    pass
            
            # Let's just use a pre-calculated bull market flag!
            
            if sym == 'SOXL':
                # Simplified check for this run: we will pass the logic in the script!
                pass

