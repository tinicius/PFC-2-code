ORTOOLS_PREFIX = /home/vinicius/.local/opt/ortools
BUILD_DIR = build

all: $(BUILD_DIR)/algo_runner
	cp $(BUILD_DIR)/algo_runner .

$(BUILD_DIR)/Makefile: CMakeLists.txt
	mkdir -p $(BUILD_DIR)
	cmake -S . -B $(BUILD_DIR) -DCMAKE_PREFIX_PATH=$(ORTOOLS_PREFIX)

$(BUILD_DIR)/algo_runner: $(BUILD_DIR)/Makefile
	cmake --build $(BUILD_DIR) -j4

clean:
	rm -rf $(BUILD_DIR) algo_runner algo_runner.o simple_heuristic.o

.PHONY: all clean
