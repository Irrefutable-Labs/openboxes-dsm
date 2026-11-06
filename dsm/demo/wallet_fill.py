#!/usr/bin/env python3
"""Type into a field on the real wallet's screen: wallet_fill.py SERIAL_PART ELEMENT_ID VALUE.
React keeps a field's value itself, so the value goes through the element's own setter and an input event, as typing does."""
import json
import os
import subprocess
import sys

part, element, value = sys.argv[1:4]
cdp = os.path.join(os.path.dirname(os.path.abspath(__file__)), "wallet_cdp.py")
script = ("(function(){var e=document.getElementById(%s);if(!e)return 'no field '+%s;"
          "var p=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;"
          "Object.getOwnPropertyDescriptor(p,'value').set.call(e,%s);"
          "e.dispatchEvent(new Event('input',{bubbles:!0}));e.dispatchEvent(new Event('change',{bubbles:!0}));return e.id+'='+e.value;})()"
          % (json.dumps(element), json.dumps(element), json.dumps(value)))
subprocess.run(["python3", cdp, part, script], check=True)
