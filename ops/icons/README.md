# Icons

SDMxDW uses a few icons from [Lucide](https://lucide.dev) (ISC License), inlined as SVG by the
Thymeleaf fragment `templates/fragments/icons.html` - no CDN, no icon font.

To add an icon: find its name on lucide.dev, add it to `ICONS` in `build_icons.py`, then

```bash
mkdir /tmp/lucide && cd /tmp/lucide && npm pack lucide-static && tar xzf lucide-static-*.tgz
python3 -I ops/icons/build_icons.py /tmp/lucide/package .     # from the repository root
```

The script rewrites the fragment and the licence file `static/licenses/lucide-LICENSE.txt`.
Use it as `<span th:replace="~{fragments/icons :: icon('trash-2')}"></span>`; icon-only buttons
need a `title` and an `aria-label`.
