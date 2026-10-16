import pandas as pd

df = pd.read_csv('data/SOXL_1min_2020_2026.csv', nrows=50000)
df['timestamp'] = pd.to_datetime(df['timestamp'])
df.set_index('timestamp', inplace=True)
df = df.tz_convert('America/New_York')

unique_hours = df.index.hour.unique().sort_values()
print("Unique hours in data (EST):", unique_hours.tolist())
