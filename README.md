# AxonIQ Console Framework Client

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.axoniq.console/console-framework-client/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.axoniq.console/console-framework-client)

AxonIQ Console superpowers your Axon Framework application with advanced monitoring and enabling easy access to actions
within the framework.

![Screenshot of the handler performance screen](.github/img/screenshot_handler_performance.png)

This repository contains the Open-Source connectors that your application will use through maven dependencies.
For actual configuration, please consult the setup instructions that will be provided by AxonIQ Console itself.

[You can visit AxonIQ Console here.](https://console.axoniq.io)

## Spring Boot Starter

### application properties

* `axoniq.console.credentials` - this need to be set, otherwise the auto configuration won't do anything. The correct
  value can be retrieved via the AxonIQ console UI.
* `axoniq.console.dlq-mode` - change the mode to access the messages in the DLQ and be able to retry them from console,
  default: `none`. Several options are available:
    * `none` - nothing is accessed, it will seem as if the dead letters are not even there.
    * `masked` - message payloads and diagnostic data won't be shown. The aggregate identifier will be hashed. The event
      type and the optional exception will be shown as-is.
    * `limited` - message payloads won't be shown. The aggregate identifier will be shown as-is. From the diagnostics
      only the keys set in the `axoniq.console.dlq-diagnostics-whitelist` will be shown.
    * `full` - all the message payloads, aggregate identifier, and the diagnostics data will be visible.

## Data sent to AxonIQ

AxonIQ Console is an [AxonIQ](https://axoniq.io) SaaS product. Your application will periodically or upon request send
information to the servers of AxonIQ. Please check our [legal documentation](https://console.axoniq.io/legal) for the
measures we implemented to protect your data.

The following data will be sent to the servers of AxonIQ:

- Event processor information
  - Name, latency, status, position
  - Occurs every 2 seconds
- Handler statistics
  - Message names and names of handling components
  - Message payload, or ids, are not sent to AxonIQ servers
  - Statistics such as latency, throughput and error rates
  - Correlation between messages and different handlers
  - Occurs every 20 seconds
- Dead Letter Information
  - Contains message name, error information and event payload
  - Occurs upon user request
  - Disabled by default

If you are concerned about the message data being sent to AxonIQ, please contact us at,
disabling the DLQ functionality will prevent that in all cases.

## How to Release

1. Run `mvn versions:set -DnewVersion=1.x.x` to update the version in the pom files
2. Commit and push the change
3. Close the milestone on GitHub
4. Wait for the release to be created on Github and publish it
5. A Github action will automatically release this to Maven Central
6. Run `mvn versions:set -DnewVersion=1.x.x-SNAPSHOT`
7. Commit and push the new development version
8. Wait for the version to become available [here](https://repo1.maven.org/maven2/io/axoniq/console/console-framework-client-parent/). Can take minutes, can take hours.
