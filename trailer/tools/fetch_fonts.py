"""Fetches IBM Plex Sans (OFL), the typeface of Tumble's logo, for the title cards.

The logo in src/main/resources/logo.png was matched against candidate typefaces
letter by letter; IBM Plex Sans SemiBold fits it best, with each letter rotated by
0, 4, 11, 21.5, 32 and 46 degrees."""
import os
import re
import urllib.request

WORK = os.environ.get("TRAILER_WORK", "/home/user/work")
UI = os.path.join(WORK, "ui")
os.makedirs(UI, exist_ok=True)
css = urllib.request.urlopen("https://fonts.googleapis.com/css2?family=IBM+Plex+Sans:wght@400;600").read().decode()
urls = re.findall(r"url\((https://[^)]+\.ttf)\)", css)
for url, name in zip(urls, ["IBMPlexSans-1.ttf", "IBMPlexSans-SemiBold.ttf"]):
    urllib.request.urlretrieve(url, os.path.join(UI, name))
print("fonts in", UI)
