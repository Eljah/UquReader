import os
from pathlib import Path

import pytest
from appium import webdriver
from appium.options.android import UiAutomator2Options
from appium.webdriver.common.appiumby import AppiumBy
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC

APK_RELATIVE_PATH = Path("android-app") / "target" / "uqureader-1.1.0.apk"
APPIUM_SERVER_URL = "http://127.0.0.1:4723"
APP_PACKAGE = "com.example.ttreader"
APP_ACTIVITY = ".MainActivity"
BUTTON_WAIT_SECONDS = 45


def build_capabilities(apk_path: Path) -> UiAutomator2Options:
    if not apk_path.is_file():
        raise FileNotFoundError(f"Build artifact {apk_path} was not found. Run the android-app Maven build first.")

    options = UiAutomator2Options()
    options.set_capability("platformName", "Android")
    options.set_capability("automationName", "UiAutomator2")
    options.set_capability("deviceName", "Android Emulator")
    options.set_capability("app", str(apk_path))
    options.set_capability("appPackage", APP_PACKAGE)
    options.set_capability("appActivity", APP_ACTIVITY)
    options.set_capability("newCommandTimeout", 240)
    options.set_capability("autoGrantPermissions", True)
    options.set_capability("adbExecTimeout", 120000)
    options.set_capability("ignoreHiddenApiPolicyError", True)
    options.set_capability("skipDeviceInitialization", True)
    options.set_capability("skipServerInstallation", True)
    return options


@pytest.mark.appium
def test_reader_navigation_buttons_are_clickable():
    apk_path = (Path(__file__).resolve().parents[2] / APK_RELATIVE_PATH).resolve()
    options = build_capabilities(apk_path)

    driver = webdriver.Remote(command_executor=APPIUM_SERVER_URL, options=options)
    try:
        wait = WebDriverWait(driver, BUTTON_WAIT_SECONDS)

        prev_button = wait.until(
            EC.element_to_be_clickable((AppiumBy.ID, f"{APP_PACKAGE}:id/pagePreviousButton"))
        )
        next_button = wait.until(
            EC.element_to_be_clickable((AppiumBy.ID, f"{APP_PACKAGE}:id/pageNextButton"))
        )

        # Tap the buttons to ensure they respond without throwing an exception.
        prev_button.click()
        next_button.click()

        assert prev_button.is_displayed(), "Previous page button should remain visible after tapping"
        assert next_button.is_displayed(), "Next page button should remain visible after tapping"
    finally:
        driver.quit()
