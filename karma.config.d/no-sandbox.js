// Chrome on this Windows environment needs the sandbox disabled to launch headless.
config.customLaunchers = config.customLaunchers || {};
config.customLaunchers.ChromeHeadlessNoSandbox = {
    base: 'ChromeHeadless',
    flags: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage']
};
config.browsers = ['ChromeHeadlessNoSandbox'];
