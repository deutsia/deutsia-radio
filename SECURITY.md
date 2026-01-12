# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.6.x   | :white_check_mark: |
| < 1.6   | :x:                |

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

Instead, please report them via GitHub's private vulnerability reporting:

1. Go to the [Security tab](https://github.com/deutsia/deutsia-radio/security)
2. Click "Report a vulnerability"
3. Fill out the form with details

You should receive a response within 72 hours. If the issue is confirmed, we will release a patch as soon as possible.

## Security Features

This app implements several security measures:

- **DNS Leak Prevention**: 47+ tests ensure no DNS leaks in force proxy modes
- **Database Encryption**: Optional SQLCipher encryption for all local data
- **Credential Protection**: AES-256-GCM encryption via Android Jetpack Security
- **Proxy Enforcement**: Fail-safe proxy modes with instant disconnect detection

## Scope

Security issues we're interested in:

- DNS/traffic leaks bypassing proxy settings
- Credential exposure or encryption weaknesses
- Authentication bypass for app lock
- Remote code execution
- Data exfiltration

Out of scope:

- Issues requiring physical device access with debugging enabled
- Social engineering attacks
- Issues in dependencies (report upstream)
- Hardware based attacks (report upstream)
