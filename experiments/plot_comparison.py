import matplotlib.pyplot as plt
import os

BASE_PATH = os.path.dirname(os.path.abspath(__file__))
ALGORITHMS = {
    'baseline':      {'color': '#7f7f7f', 'marker': 'o', 'label': 'Baseline (RR)'},
    'work-stealing': {'color': '#9467bd', 'marker': 'p', 'label': 'Work Stealing (Chunk)'},
    'ema-dynamic':   {'color': '#1f77b4', 'marker': 's', 'label': 'EMA-Dynamic'},
    'a2ws':          {'color': '#2ca02c', 'marker': '^', 'label': 'A2WS'},
    'hybrid':        {'color': '#ff7f0e', 'marker': 'D', 'label': 'Hybrid'},
    'rl':            {'color': '#d62728', 'marker': '*', 'label': 'Reinforcement Learning'}
}
WORKER_COUNTS = [2, 3, 4, 5]
OUTPUT_FILE = os.path.join(BASE_PATH, "comparison_chart.png")

def get_runtime(algo, worker_count):
    file_path = os.path.join(BASE_PATH, algo, f"runtime_{worker_count}w.txt")
    if not os.path.exists(file_path): return None 
    try:
        with open(file_path, 'r') as f: return float(f.read().strip())
    except Exception: return None

def main():
    plt.figure(figsize=(10, 6))
    has_data = False
    for algo_name, style in ALGORITHMS.items():
        x_values, y_values = [], []
        for w in WORKER_COUNTS:
            runtime = get_runtime(algo_name, w)
            if runtime is not None:
                x_values.append(w)
                y_values.append(runtime)
        if x_values:
            plt.plot(x_values, y_values, marker=style['marker'], color=style['color'], label=style['label'], linewidth=2, markersize=8)
            has_data = True

    if has_data:
        plt.title('Performance Comparison: Scheduling Algorithms', fontsize=16, fontweight='bold')
        plt.xlabel('Number of Workers', fontsize=12)
        plt.ylabel('Total Runtime (seconds)', fontsize=12)
        plt.grid(True, linestyle='--', alpha=0.7)
        plt.legend()
        plt.xticks(WORKER_COUNTS) 
        plt.savefig(OUTPUT_FILE)

if __name__ == "__main__": main()
