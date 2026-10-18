.PHONY: help test package clean

help:
	@echo "HTX Trend Research System - Build Tasks"
	@echo ""
	@echo "Usage:"
	@echo "  make test       Run all tests with pytest"
	@echo "  make package    Create versioned distribution package"
	@echo "  make clean      Remove build artifacts"
	@echo ""

test:
	python3 -m pytest tests/research/ -v

package:
	python3 scripts/package.py

clean:
	rm -rf dist/ build/ *.egg-info
	find . -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null || true
	find . -type f -name "*.pyc" -delete
	find . -type f -name "*.pyo" -delete
