import streamlit as st
import streamlit.components.v1 as components

st.set_page_config(page_title="Fuel Optimizer", page_icon="⛽", layout="wide")

with open("fuel_optimizer.html", "r") as f:
    html = f.read()

components.html(html, height=900, scrolling=True)