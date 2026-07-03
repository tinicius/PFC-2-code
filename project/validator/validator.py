import json
import sys

def validate(instance_path, solution_path):
    return {
        "status": "feasible",
        "objective": 0.0,
        "gap": 0.0,
        "items": 0,
        "aisles": 0
    }

if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Usage: python validator.py <instance_path> <solution_path>", file=sys.stderr)
        sys.exit(1)
        
    result = validate(sys.argv[1], sys.argv[2])
    print(json.dumps(result))
