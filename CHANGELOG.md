# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.3] - 2026-08-25

### Fixed
- `LZ4FrameInputStream`: clean EOF after a single frame no longer throws `LZ4Exception`; single-frame streams now decode correctly
- `LZ4FrameOutputStream.flush()`: now materializes any buffered partial block to the underlying stream, allowing callers to observe on-disk progress mid-stream

## [0.1.0] - 2026-08-24

### Added
- Initial release of the project
