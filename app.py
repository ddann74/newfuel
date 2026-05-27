import streamlit as st
import streamlit.components.v1 as components
import os

st.set_page_config(page_title="Fuel Optimizer", page_icon="⛽", layout="wide")

# Build path relative to this script file
dir = os.path.dirname(__file__)
html_path = os.path.join(dir, "fuel_optimizer.html")

with open(html_path, "r") as f:
    html = f.read()

components.html(html, height=900, scrolling=True)
