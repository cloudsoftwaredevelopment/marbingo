// Turnover = units sold ÷ average stock, where average = (opening + closing) / 2.
// A non-positive average falls back to max(opening, closing, 1) to avoid dividing by zero.
function turnover(units, opening, closing) {
  let avg = (opening + closing) / 2;
  if (!(avg > 0)) avg = Math.max(opening, closing, 1);
  return Math.round((units / avg) * 10) / 10;
}

module.exports = { turnover };
