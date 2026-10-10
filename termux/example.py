#!/usr/bin/env python3
"""Example: drive Gecko Browser from Termux.

    export BROWSER_PORT=9876 BROWSER_TOKEN=...   # from the app's Remote control dialog
    python example.py
"""

from browser_control import Browser, BrowserError

b = Browser()
print("Connected:", b.status()["tab_count"], "tab(s) open")

# Load a page in the current tab and wait for it to finish loading.
b.goto("https://en.wikipedia.org/wiki/Special:Search")
print("Loaded:", b.title())

# Fill the search box and submit the form.
b.fill("input[name=search]", "GeckoView", submit=True)
b.wait_load()
b.wait_for("#firstHeading")
print("Now on:", b.title(), "-", b.url())

# Read things from the page with JavaScript.
heading = b.eval("document.querySelector('#firstHeading').textContent")
links = b.eval("return Array.from(document.querySelectorAll('#mw-content-text p a')).slice(0, 5).map(a => a.href)")
print("Heading:", heading)
print("First links:", *links, sep="\n  ")

# Work with tabs.
tab = b.new_tab("https://example.com")
print("Opened tab", tab["index"], tab["title"])
print("Paragraph:", b.text("p", tab=tab["id"]))
b.close_tab(tab["id"])

try:
    b.click("#this-element-does-not-exist")
except BrowserError as error:
    print("Expected error:", error)
