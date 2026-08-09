import streamlit as st
import streamlit.components.v1 as components
from pathlib import Path

st.set_page_config(page_title="Fuel Optimizer", page_icon="⛽", layout="wide")

STATIC_DIR = Path(__file__).parent / "static"

html = (STATIC_DIR / "index.html").read_text()
css = (STATIC_DIR / "style.css").read_text().rstrip("\n")
js = (STATIC_DIR / "app.js").read_text().rstrip("\n")

html = html.replace("{{CSS}}", css).replace("{{JS}}", js)

components.html(html, height=900, scrolling=True)
