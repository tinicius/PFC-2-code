instances = 20 + 15 + 15
time_limit_s = 600 # 10 minutes

executions = 2

threads = 30

total_seconds = (executions * instances * time_limit_s) / threads
hours = int(total_seconds // 3600)
minutes = int((total_seconds % 3600) // 60)

print(f"Experiment time: {hours}h {minutes}m")