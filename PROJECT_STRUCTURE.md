# Project Structure

Complete overview of the SignalBot project structure and file organization.

## Directory Tree

```
SignalBot/
├── .github/
│   └── workflows/
│       └── test.yml              # GitHub Actions CI/CD pipeline
├── src/                          # Source code
│   ├── __init__.py
│   ├── bot.py                    # Main bot loop and logic
│   ├── signald_client.py         # signald API client
│   ├── store.py                  # State persistence
│   ├── metrics.py                # Metrics tracking
│   ├── template.py               # Message templating
│   └── filters.py                # Member filtering (allowlist/blocklist/rate limiting)
├── tests/                        # Unit tests
│   ├── __init__.py
│   ├── test_bot.py
│   ├── test_signald_client.py
│   ├── test_store.py
│   ├── test_metrics.py
│   └── test_config_validation.py
├── main.py                       # Entry point and CLI
├── config.example.yaml           # Example configuration
├── requirements.txt              # Python dependencies
├── requirements-dev.txt          # Development dependencies
├── run_tests.py                  # Test runner
├── Dockerfile                    # Docker image definition
├── docker-compose.yml            # Docker Compose configuration
├── .dockerignore                 # Docker build exclusions
├── .gitignore                    # Git exclusions
├── README.md                     # Main documentation
├── QUICKSTART.md                 # Quick start guide
├── DOCKER.md                     # Docker deployment guide
├── CHANGELOG.md                  # Version history
├── LICENSE                       # MIT License
├── IMPLEMENTATION_SUMMARY.md     # Implementation details
└── PROJECT_STRUCTURE.md          # This file
```

## File Descriptions

### Core Application Files

#### `main.py`
- Entry point for the application
- CLI argument parsing
- Configuration loading and validation
- Command routing (run, dry-run, validate, stats, etc.)
- Logging setup

#### `src/bot.py`
- Main bot loop
- Poll for pending members
- Message sending logic
- Approval logic (manual/automatic)
- Filter integration
- Metrics tracking
- Graceful shutdown handling

#### `src/signald_client.py`
- signald socket/TCP communication
- Request/response handling
- Retry logic with exponential backoff
- Error handling (SignaldError, SignaldConnectionError)
- API methods: list_pending_members, send_message, approve_membership

#### `src/store.py`
- State persistence (JSON file)
- Track messaged members
- Cooldown enforcement
- Automatic backups
- Corruption recovery
- Statistics

#### `src/metrics.py`
- Metrics collection
- Success/failure tracking
- Uptime monitoring
- Error categorization
- Statistics reporting

#### `src/template.py`
- Message template engine
- Variable substitution
- Template validation
- Available variables: timestamp, date, time, datetime, member_uuid, member_number

#### `src/filters.py`
- Member filtering logic
- Allowlist/blocklist with wildcards
- Rate limiting
- Filter decision logging

### Configuration Files

#### `config.example.yaml`
- Example configuration with all options
- Inline documentation
- Template examples
- Filter examples

#### `requirements.txt`
- Python package dependencies
- PyYAML for configuration

#### `requirements-dev.txt`
- Development dependencies
- Testing tools (pytest, pytest-cov)
- Code quality tools (black, flake8, mypy)

### Docker Files

#### `Dockerfile`
- Multi-stage Python build
- Non-root user for security
- Health checks
- Optimized layer caching

#### `docker-compose.yml`
- Service definition
- Volume mounts
- Environment variables
- Logging configuration

#### `.dockerignore`
- Exclude unnecessary files from Docker build
- Reduce image size

### Test Files

#### `tests/test_*.py`
- Unit tests for each module
- 40+ test cases total
- Mock-based testing for external dependencies

#### `run_tests.py`
- Simple test runner
- Uses unittest discovery
- Returns appropriate exit codes

### Documentation Files

#### `README.md`
- Main project documentation
- Feature overview
- Installation instructions
- Configuration guide
- Usage examples
- Troubleshooting

#### `QUICKSTART.md`
- 5-minute setup guide
- Common configuration examples
- Quick reference commands
- Troubleshooting checklist

#### `DOCKER.md`
- Docker deployment guide
- signald connection options
- Production deployment tips
- Troubleshooting Docker issues

#### `CHANGELOG.md`
- Version history
- Feature additions
- Bug fixes
- Breaking changes

#### `IMPLEMENTATION_SUMMARY.md`
- Detailed implementation notes
- Architecture evolution
- Statistics and metrics
- Future enhancements

#### `PROJECT_STRUCTURE.md`
- This file
- Project organization
- File descriptions
- Module relationships

### CI/CD Files

#### `.github/workflows/test.yml`
- GitHub Actions workflow
- Multi-OS testing (Linux, Windows, macOS)
- Multi-Python version testing (3.10, 3.11, 3.12)
- Linting and formatting checks
- Docker build verification
- Code coverage reporting

### Other Files

#### `.gitignore`
- Git exclusions
- Ignore config.yaml (user-specific)
- Ignore data files (messaged.json, metrics.json)
- Ignore Python artifacts

#### `LICENSE`
- MIT License
- Open source license

## Module Dependencies

```
main.py
├── src.bot
│   ├── src.signald_client
│   ├── src.store
│   ├── src.metrics
│   ├── src.template
│   └── src.filters
└── yaml (PyYAML)

src.bot
├── src.signald_client
│   └── socket (stdlib)
├── src.store
│   └── json (stdlib)
├── src.metrics
│   └── json (stdlib)
├── src.template
│   └── re (stdlib)
└── src.filters
    └── re (stdlib)
```

## Data Flow

```
User
  ↓
main.py (CLI)
  ↓
config.yaml → validate_config()
  ↓
run_bot()
  ↓
┌─────────────────────────────────────┐
│ Bot Loop                            │
│  1. Poll signald for pending        │
│  2. Check filters (allowlist/block) │
│  3. Check rate limits               │
│  4. Render message template         │
│  5. Send message via signald        │
│  6. Update store                    │
│  7. Record metrics                  │
│  8. Auto-approve (if enabled)       │
└─────────────────────────────────────┘
  ↓
State Files:
  - messaged.json (store)
  - metrics.json (metrics)
  - messaged.json.backup* (backups)
```

## Configuration Flow

```
config.yaml
  ↓
load_config() in main.py
  ↓
validate_config()
  ↓
run_bot()
  ↓
Initialize components:
  - SignaldClient (with retry config)
  - Store (with backup config)
  - Metrics (with enabled flag)
  - MemberFilter (with allowlist/blocklist)
  - RateLimiter (with rate config)
  - MessageTemplate (with template string)
```

## Testing Structure

```
tests/
├── test_store.py
│   └── TestStore (10+ test cases)
├── test_metrics.py
│   └── TestMetrics (10+ test cases)
├── test_signald_client.py
│   ├── TestSignaldClient (12+ test cases)
│   ├── TestSignaldError
│   └── TestSignaldConnectionError
└── test_config_validation.py
    └── TestConfigValidation (8+ test cases)
```

## Deployment Options

### 1. Manual Deployment
```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python main.py
```

### 2. Docker Deployment
```bash
docker-compose up -d
```

### 3. Systemd Service
```ini
[Unit]
Description=SignalBot
After=network.target signald.service

[Service]
Type=simple
User=signalbot
WorkingDirectory=/opt/signalbot
ExecStart=/opt/signalbot/.venv/bin/python main.py
Restart=always

[Install]
WantedBy=multi-user.target
```

## Key Design Decisions

### 1. Modular Architecture
- Each feature in separate module
- Clear separation of concerns
- Easy to test and maintain

### 2. Configuration-Driven
- All behavior configurable via YAML
- No hardcoded values
- Environment variable overrides

### 3. Fail-Safe Design
- Retry logic for transient failures
- Graceful degradation
- Automatic backups
- Corruption recovery

### 4. Observable
- Comprehensive logging
- Metrics tracking
- Statistics reporting
- Debug mode

### 5. Production-Ready
- Docker support
- Health checks
- Non-root execution
- Resource limits
- CI/CD pipeline

## Development Workflow

1. **Setup**: `pip install -r requirements-dev.txt`
2. **Code**: Edit source files in `src/`
3. **Test**: `python run_tests.py` or `pytest`
4. **Lint**: `flake8 src tests`
5. **Format**: `black src tests main.py`
6. **Type Check**: `mypy src`
7. **Commit**: Git commit with descriptive message
8. **CI**: GitHub Actions runs tests automatically

## Extension Points

### Adding New Commands
1. Add command handler function in `main.py`
2. Add command to CLI parser in `main()`
3. Update help text
4. Add tests

### Adding New Filters
1. Add filter class in `src/filters.py`
2. Initialize in `run_bot()` in `src/bot.py`
3. Apply filter in bot loop
4. Add config options in `config.example.yaml`
5. Add tests

### Adding New Template Variables
1. Add variable to `MessageTemplate.VARIABLES` in `src/template.py`
2. Add variable to context in `render()` method
3. Update documentation
4. Add tests

### Adding New Metrics
1. Add metric method in `src/metrics.py`
2. Call metric method in appropriate place
3. Add to `get_stats()` output
4. Update stats display in `main.py`

## Performance Considerations

- **Polling**: Default 120s interval (configurable)
- **Retries**: Max 3 retries with exponential backoff
- **Backups**: Keep last 5 timestamped backups
- **Memory**: Minimal (< 50MB typical)
- **CPU**: Low (mostly waiting on I/O)
- **Disk**: Store files grow slowly (~1KB per 100 members)

## Security Considerations

- **Non-root Docker user**: UID 1000
- **No secrets in code**: All config via files/env vars
- **Read-only config mount**: Docker volume mounted read-only
- **Socket permissions**: Requires access to signald socket
- **Rate limiting**: Prevent abuse
- **Input validation**: Config validation on startup

## Maintenance

### Regular Tasks
- Monitor metrics and logs
- Review error rates
- Update dependencies
- Backup data files
- Review and update filters

### Troubleshooting
1. Check logs (verbose mode)
2. Validate configuration
3. Test in dry-run mode
4. Check signald connectivity
5. Review metrics

### Updating
1. Pull latest code
2. Review CHANGELOG.md
3. Update configuration if needed
4. Run tests
5. Deploy new version
6. Monitor for issues
