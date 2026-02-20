SCRIPT_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
OUTPUT_DIR := $(SCRIPT_DIR)output
ARCHIVE := $(OUTPUT_DIR)/sdk-dist/pglite-wasi.tar.xz
RESOURCES_DIR := $(SCRIPT_DIR)../core/src/main/resources
WASM_FILE := $(OUTPUT_DIR)/pglite.wasi

.PHONY: build clean unpack

WASM_OPT_FLAGS ?= -Oz --strip-debug

build:
	WASM_OPT_FLAGS="$(WASM_OPT_FLAGS)" $(SCRIPT_DIR)build.sh

clean:
	$(SCRIPT_DIR)clean.sh
	rm -rf $(OUTPUT_DIR)/tmp
	rm -f $(WASM_FILE)
	rm -rf $(RESOURCES_DIR)/pglite
	rm -f $(RESOURCES_DIR)/pglite-files.txt

unpack: $(ARCHIVE)
	@echo "=== Unpacking pglite-wasi.tar.xz ==="
	rm -rf $(OUTPUT_DIR)/tmp
	rm -rf $(RESOURCES_DIR)/pglite
	rm -f $(RESOURCES_DIR)/pglite-files.txt
	mkdir -p $(OUTPUT_DIR)/tmp
	tar -xJf $(ARCHIVE) -C $(OUTPUT_DIR)/tmp
	@echo "=== Copying pglite.wasi ==="
	cp $(OUTPUT_DIR)/tmp/tmp/pglite/bin/pglite.wasi $(WASM_FILE)
	@echo "=== Copying distribution files to core/src/main/resources/pglite/ ==="
	mkdir -p $(RESOURCES_DIR)
	cp -r $(OUTPUT_DIR)/tmp/tmp/pglite $(RESOURCES_DIR)/pglite
	rm -f $(RESOURCES_DIR)/pglite/bin/pglite.wasi
	@echo "=== Generating pglite-files.txt manifest ==="
	cd $(RESOURCES_DIR) && find pglite -type f | sort > $(RESOURCES_DIR)/pglite-files.txt
	@echo "=== Done ==="
	ls -lh $(WASM_FILE)
	wc -l $(RESOURCES_DIR)/pglite-files.txt

$(ARCHIVE):
	@echo "Error: Archive not found at $(ARCHIVE)"
	@echo "Run 'make build' first"
	@exit 1
