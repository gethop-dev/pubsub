# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/en/1.0.0/).

## [Unreleased]

## [0.4.0] - 2023-03-12
### Added
- Added support for AMQP event listeners to AMQP pubsub client. This could be configured by the library consumer later, once the Integrant key was initialized. But doing it as part of the Integrant key initialization is more convenient. Now this can be configured using the `:listeners` optional key, inside the `:brocker-config` key [[Issue #5]].

### Fixed
- Fixed support for :opts key in AMQP pubsub client broker configuration. README.md documented that there was an optional :opts key in the broker configuration that could be used to pass additional AMQP connection options to the underlying library. But the whole :opts key (and associated value) was passed in as another AMQP connection parameter. Which meant the underlying AMQP library completely ignored it (as it doesn't use such :opts key at all) [[Issue #6]].
- dev.gethop.pubsub.amqp/halt-key! no longer throws an exception if the call to langohr.core/close throws (it can throw IOException under certain circumstances)

### Changed
- Bumped dependencies to newer versions

## [0.3.6] - 2022-05-26
### Changed
- Moving the repository to [gethop-dev](https://github.com/gethop-dev) organization
- CI/CD solution switch from [TravisCI](https://travis-ci.org/) to [GitHub Actions](Ihttps://github.com/features/actions)
- `lein`, `cljfmt` and `eastwood` dependencies bump
- Fix several `eastwood` and `clj-kondo` warnings
- update this changelog's releases tags links

### Added
- Source code linting using [clj-kondo](https://github.com/clj-kondo/clj-kondo)

## [0.3.5] - 2020-09-23
### Added
- Added `ig/suspend-key!` and `ig/resume-key` Integrant keys so that we don't disconnect and reconnect when executing `(reset)` in Duct dev environments unless the configuration for the keys have changed.

## [0.3.4] - 2020-08-08
### Fixed
- The fix in 0.3.3 for custom SSL configurations was only applied to the MQTT implementation. The fix is now applied to the AMQP implementation too.
- The underlying libraries can throw exceptions in the publish, subscribe, unsubscribe, etc. operations. We now catch them and return sensible values in each case.

## [0.3.3] - 2020-07-08
### Fixed
- Only apply custom SSL configuration when defined. Before it was always applied when we specified that the connection used SSL, even if it was not defined. Now we only apply it when it is explicitly defined.

## [0.3.2] - 2020-05-02
### Changed
- Bumped dependencies to newer versions

### Added
- Added this changelog

## [0.3.1] - 2019-02-26
### Changed
- Bumped minimum Leiningen version to 2.9.0
- Reorganized dev profile definition
- Fixed typos in README.md and added more examples

### Added
- Add Travis CI integration
- Add deployment configuration and integration tests CI 

## [0.3.0] - 2019-02-26
- Initial commit (previous versions were not publicly released)

[UNRELEASED]: https://github.com/gethop-dev/pubsub/compare/v0.3.6...HEAD
[0.3.6]: https://github.com/gethop-dev/pubsub/compare/v0.3.5...v0.3.6
[0.3.5]: https://github.com/gethop-dev/pubsub/compare/v0.3.4...v0.3.5
[0.3.4]: https://github.com/gethop-dev/pubsub/compare/v0.3.3...v0.3.4
[0.3.3]: https://github.com/gethop-dev/pubsub/compare/v0.3.2...v0.3.3
[0.3.2]: https://github.com/gethop-dev/pubsub/compare/v0.3.1...v0.3.2
[0.3.1]: https://github.com/gethop-dev/pubsub/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/gethop-dev/pubsub/releases/tag/v0.3.0

[Issue #5]: https://github.com/gethop-dev/pubsub/issues/5
[Issue #6]: https://github.com/gethop-dev/pubsub/issues/6
