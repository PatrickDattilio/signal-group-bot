# Changelog

All notable changes to SignalBot will be documented in this file.

## [2.0.0] - 2026-01-31

### Added

#### Core Features
- **Message Templates**: Support for dynamic variables in messages (`{{datetime}}`, `{{member_uuid}}`, etc.)
- **Member Filtering**: Allowlist and blocklist support with wildcard patterns
- **Rate Limiting**: Prevent spam from repeated join requests
- **Metrics Tracking**: Comprehensive metrics for messages, approvals, and errors
- **Graceful Shutdown**: Proper signal handling (SIGINT/SIGTERM)
- **Retry Logic**: Exponential backoff for failed signald connections
- **Backup System**: Automatic backups of state files with corruption recovery

#### Commands
- `dry-run` - Test mode without sending messages
- `validate` - Validate configuration file
- `stats` - Display bot statistics
- `help` - Show usage information
- `-v/--verbose` - Enable debug logging

#### Configuration
- `signald.max_retries` - Configure retry attempts
- `signald.timeout` - Socket timeout configuration
- `filters.allowlist` - Allowlist configuration
- `filters.blocklist` - Blocklist configuration
- `filters.rate_limit` - Rate limiting settings

#### Development
- Comprehensive unit test suite
- Docker support with docker-compose
- Development requirements file
- Test runner script
- Configuration validation

#### Documentation
- Enhanced README with all new features
- Docker deployment guide (DOCKER.md)
- Changelog (this file)
- Improved inline code documentation

### Changed
- **Error Handling**: More robust error handling with specific exception types
- **Logging**: Structured logging with configurable levels and file output
- **Store**: Atomic file writes and backup restoration
- **Configuration**: Validation on startup with detailed error messages

### Fixed
- Connection retry logic now uses exponential backoff
- Store file corruption is handled with automatic backup recovery
- Proper cleanup of old backup files

## [1.0.0] - Initial Release

### Added
- Basic bot functionality
- Manual and automatic approval modes
- Cooldown period for re-messaging
- Simple message sending
- State tracking in JSON file
- Basic configuration via YAML
