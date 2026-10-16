import pandas as pd
import yfinance as yf

df_soxx_daily = yf.download('SOXX', start='2019-01-01', end='2026-12-31', progress=False)
if isinstance(df_soxx_daily.columns, pd.MultiIndex):
    df_soxx_daily.columns = df_soxx_daily.columns.droplevel(1)
df_soxx_daily = df_soxx_daily[['Close']].rename(columns={'Close': 'SOXX_Close'})
df_soxx_daily['Prev_Close'] = df_soxx_daily['SOXX_Close'].shift(1)
df_soxx_daily['Pct_Change'] = (df_soxx_daily['SOXX_Close'] / df_soxx_daily['Prev_Close'] - 1.0) * 100.0

drops = df_soxx_daily[df_soxx_daily['Pct_Change'] <= -7.0]
print("SOXX Drops worse than -7.0%:")
print(drops[['Pct_Change']])
