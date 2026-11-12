"""Writes templates/fragments/icons.html of SDMxDW (see README.md) from lucide-static SVGs (inner elements only).

  build_icons.py <lucide package dir> <sdmxdw repo dir>
"""
import pathlib, re, sys

ICONS = ["pencil", "trash-2", "undo-2", "plus", "copy", "x", "chevron-up", "chevron-down",
         "download", "ban"]
pkg, repo = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
version = re.search(r'"version":\s*"([^"]+)"', (pkg / "package.json").read_text()).group(1)
cases = []
for name in ICONS:
    svg = (pkg / "icons" / f"{name}.svg").read_text()
    inner = re.search(r">\s*(.*)</svg>", svg[svg.index("<svg"):], re.S).group(1)
    inner = " ".join(line.strip() for line in inner.strip().splitlines())
    cases.append(f'        <th:block th:case="\'{name}\'">{inner}</th:block>')
html = f'''<!DOCTYPE html>
<!--
  Icons from Lucide (https://lucide.dev) v{version}, ISC License, see /licenses/lucide-LICENSE.txt.
  Generated: only the icons SDMxDW uses. Add one: see ops/icons/README.md.

  Usage: <span th:replace="~{{fragments/icons :: icon('trash-2')}}"></span>
  The icon takes the text colour (currentColor) and is 1em by default; give buttons an aria-label.
-->
<html xmlns:th="http://www.thymeleaf.org">
<body>
<svg th:fragment="icon(name)" class="lucide inline-block w-[1.1em] h-[1.1em] align-[-0.15em]" xmlns="http://www.w3.org/2000/svg"
     viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"
     aria-hidden="true" focusable="false">
    <th:block th:switch="${{name}}">
{chr(10).join(cases)}
    </th:block>
</svg>
</body>
</html>
'''
(repo / "src/main/resources/templates/fragments/icons.html").write_text(html)
lic = repo / "src/main/resources/static/licenses"
lic.mkdir(parents=True, exist_ok=True)
(lic / "lucide-LICENSE.txt").write_text((pkg / "LICENSE").read_text())
print(len(ICONS), "icons, lucide", version)
