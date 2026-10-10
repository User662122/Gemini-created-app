"""Example: drive the browser from Python.

Before running:
  1. Open the app, tap the Automation icon, and turn on "Local control API".
  2. Copy the access token into the environment:
       export GECKO_BROWSER_TOKEN=<token>
  3. From a PC over USB, forward the port:
       adb forward tcp:8765 tcp:8765

Then:  python3 example.py
"""

from gecko_browser import Browser


def main() -> None:
    browser = Browser()  # uses GECKO_BROWSER_TOKEN and http://127.0.0.1:8765
    print("status:", browser.status())

    tab = browser.new_tab("https://example.com")
    tab.wait_until_loaded()
    print("title:", tab.title)
    print("h1 text:", tab.text("h1"))
    print("document.title via JS:", tab.eval("document.title"))
    print("links on the page:", tab.eval("document.links.length"))

    # Open a second tab and use the page's own form, if it has one.
    search = browser.new_tab("https://duckduckgo.com")
    search.wait_until_loaded()
    search.wait_for("input[name=q]", timeout=20)
    search.type("input[name=q]", "gecko browser automation")
    search.click("button[type=submit]")
    search.wait_until_loaded()
    print("after search:", search.url)

    # Clean up.
    search.close()
    tab.close()


if __name__ == "__main__":
    main()
