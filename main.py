instances = 180
time_limit_s = 60 # 10 minutes

executions = 1

threads = 12

total_seconds = (executions * instances * time_limit_s) / threads
hours = int(total_seconds // 3600)
minutes = int((total_seconds % 3600) // 60)

print(f"Experiment time: {hours}h {minutes}m")