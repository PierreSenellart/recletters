var PREFIX = "/";

function changeYear(y)
{
  window.location = 
    PREFIX + "changeYear?year=" + parseInt(y) + "&redir=" + 
    window.location.pathname +
    encodeURIComponent(window.location.search)
}

function onLoad() {
  var select = document.getElementById("select_year");
  select.addEventListener("change", function() { changeYear(select.value) });
}

window.addEventListener("load", onLoad);
