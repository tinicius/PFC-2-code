instances = 20 + 15 + 15
time_limit_s = 600 # 10 minutes

executions = 2

threads = 12

print(f"Experiment time: {(executions * instances * time_limit_s / 3600) / threads:.2f} hours") 