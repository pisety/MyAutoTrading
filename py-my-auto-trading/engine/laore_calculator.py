import math
import logging

logger = logging.getLogger("LaoreCalculator")

def calculate_daily_orders(settings, status, current_price):
    """
    Calculate the daily LOC orders based on the Laore Infinite Buying VR rules.
    """
    orders = {'buy': [], 'sell': []}
    
    total_budget = settings.get("total_budget", 0)
    split_count = settings.get("split_count", 40)
    target_yield = settings.get("target_yield_percent", 10.0) / 100.0
    
    current_round = status.get("current_round", 0)
    avg_price = status.get("average_price", 0.0)
    holding_qty = status.get("holding_quantity", 0.0)
    current_mode = status.get("current_mode", "일반 모드(전반전)")
    
    daily_budget = total_budget / split_count if split_count > 0 else 0
    if daily_budget <= 0 or current_price <= 0:
        return orders
        
    if avg_price > 0:
        target_sell_price = avg_price * (1 + target_yield)
    else:
        target_sell_price = 0.0
        
    if holding_qty > 0 and target_sell_price > 0:
        orders['sell'].append({
            'price': round(target_sell_price, 2),
            'qty': holding_qty,
            'type': 'LOC'
        })
        
    base_qty = math.floor(daily_budget / current_price)
    if base_qty < 2:
        base_qty = 2 
        
    half_qty = base_qty // 2
    
    if "일반 모드" in current_mode:
        if current_round < (split_count / 2):
            orders['buy'].append({
                'price': round(avg_price if avg_price > 0 else current_price, 2),
                'qty': half_qty,
                'type': 'LOC'
            })
            orders['buy'].append({
                'price': round(current_price * 1.10, 2),
                'qty': base_qty - half_qty,
                'type': 'LOC'
            })
        else:
            orders['buy'].append({
                'price': round(avg_price, 2),
                'qty': half_qty,
                'type': 'LOC'
            })
            if current_price < avg_price:
                orders['buy'].append({
                    'price': round(current_price * 1.10, 2),
                    'qty': base_qty - half_qty,
                    'type': 'LOC'
                })
                
        # 폭락 대비
        orders['buy'].append({
            'price': round(current_price * 0.95, 2),
            'qty': 1,
            'type': 'LOC'
        })
        orders['buy'].append({
            'price': round(current_price * 0.90, 2),
            'qty': 1,
            'type': 'LOC'
        })

    return orders
