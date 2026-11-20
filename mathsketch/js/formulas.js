/* SketchSolve — formula library.
 * Each formula: name, equation (formula-style names), and what each
 * letter means with its usual unit. `value` pre-fills a constant. */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.Formulas = api;
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  const CATEGORIES = [
    { id: 'geo2', name: 'Geometry — flat shapes', icon: '△' },
    { id: 'geo3', name: 'Geometry — solids', icon: '⬡' },
    { id: 'trig', name: 'Trigonometry', icon: '∠' },
    { id: 'alg', name: 'Algebra & graphs', icon: 'x²' },
    { id: 'elec', name: 'Electrical', icon: 'Ω' },
    { id: 'phys', name: 'Physics & motion', icon: '→' },
    { id: 'chem', name: 'Chemistry', icon: '⚗' },
    { id: 'money', name: 'Money', icon: '$' },
    { id: 'every', name: 'Everyday & conversions', icon: '⇄' }
  ];

  const F = (cat, name, eq, vars, note) => ({ cat, name, eq, vars, note });

  const LIST = [
    // ---------- flat shapes
    F('geo2', 'Rectangle area', 'A = l*w', { A: ['area', 'units²'], l: ['length', 'units'], w: ['width', 'units'] }),
    F('geo2', 'Rectangle perimeter', 'P = 2*l + 2*w', { P: ['perimeter', 'units'], l: ['length', 'units'], w: ['width', 'units'] }),
    F('geo2', 'Square area', 'A = s^2', { A: ['area', 'units²'], s: ['side', 'units'] }),
    F('geo2', 'Triangle area', 'A = 1/2*b*h', { A: ['area', 'units²'], b: ['base', 'units'], h: ['height (square to the base)', 'units'] }),
    F('geo2', 'Pythagorean theorem', 'c^2 = a^2 + b^2', { c: ['hypotenuse (longest side)', 'units'], a: ['leg', 'units'], b: ['other leg', 'units'] }, 'Right triangles only.'),
    F('geo2', 'Triangle angles add to 180°', 'A + B + C = 180', { A: ['angle A', '°'], B: ['angle B', '°'], C: ['angle C', '°'] }),
    F('geo2', "Heron's formula (area from 3 sides)", 'Area = sqrt(s*(s - a)*(s - b)*(s - c))', { Area: ['area', 'units²'], a: ['side a', 'units'], b: ['side b', 'units'], c: ['side c', 'units'], s: ['half the perimeter: (a+b+c)/2', 'units'] }, 'Work out s = (a + b + c)/2 first.'),
    F('geo2', 'Parallelogram area', 'A = b*h', { A: ['area', 'units²'], b: ['base', 'units'], h: ['height', 'units'] }),
    F('geo2', 'Trapezoid area', 'A = (a + b)/2*h', { A: ['area', 'units²'], a: ['top (parallel side)', 'units'], b: ['bottom (parallel side)', 'units'], h: ['height', 'units'] }),
    F('geo2', 'Circle area', 'A = pi*r^2', { A: ['area', 'units²'], r: ['radius', 'units'] }),
    F('geo2', 'Circle circumference', 'C = 2*pi*r', { C: ['circumference', 'units'], r: ['radius', 'units'] }),
    F('geo2', 'Diameter and radius', 'd = 2*r', { d: ['diameter', 'units'], r: ['radius', 'units'] }),
    F('geo2', 'Arc length', 's = theta/360*2*pi*r', { s: ['arc length', 'units'], theta: ['angle at the centre', '°'], r: ['radius', 'units'] }),
    F('geo2', 'Sector area', 'A = theta/360*pi*r^2', { A: ['sector area', 'units²'], theta: ['angle at the centre', '°'], r: ['radius', 'units'] }),
    F('geo2', 'Ellipse area', 'A = pi*a*b', { A: ['area', 'units²'], a: ['half-width', 'units'], b: ['half-height', 'units'] }),
    F('geo2', 'Interior angle of a regular polygon', 'angle = (n - 2)*180/n', { angle: ['each inside angle', '°'], n: ['number of sides', ''] }),
    F('geo2', 'Regular polygon area', 'A = 1/2*P*ap', { A: ['area', 'units²'], P: ['perimeter', 'units'], ap: ['apothem (centre to middle of a side)', 'units'] }),

    // ---------- solids
    F('geo3', 'Cube volume', 'V = s^3', { V: ['volume', 'units³'], s: ['edge', 'units'] }),
    F('geo3', 'Box (rectangular prism) volume', 'V = l*w*h', { V: ['volume', 'units³'], l: ['length', 'units'], w: ['width', 'units'], h: ['height', 'units'] }),
    F('geo3', 'Box surface area', 'SA = 2*(l*w + l*h + w*h)', { SA: ['surface area', 'units²'], l: ['length', 'units'], w: ['width', 'units'], h: ['height', 'units'] }),
    F('geo3', 'Cylinder volume', 'V = pi*r^2*h', { V: ['volume', 'units³'], r: ['radius', 'units'], h: ['height', 'units'] }),
    F('geo3', 'Cylinder surface area', 'SA = 2*pi*r^2 + 2*pi*r*h', { SA: ['surface area', 'units²'], r: ['radius', 'units'], h: ['height', 'units'] }),
    F('geo3', 'Cone volume', 'V = 1/3*pi*r^2*h', { V: ['volume', 'units³'], r: ['base radius', 'units'], h: ['height', 'units'] }),
    F('geo3', 'Cone slant height', 'l^2 = r^2 + h^2', { l: ['slant height', 'units'], r: ['base radius', 'units'], h: ['height', 'units'] }),
    F('geo3', 'Sphere volume', 'V = 4/3*pi*r^3', { V: ['volume', 'units³'], r: ['radius', 'units'] }),
    F('geo3', 'Sphere surface area', 'SA = 4*pi*r^2', { SA: ['surface area', 'units²'], r: ['radius', 'units'] }),
    F('geo3', 'Pyramid volume', 'V = 1/3*B*h', { V: ['volume', 'units³'], B: ['area of the base', 'units²'], h: ['height', 'units'] }),
    F('geo3', 'Prism volume (any base)', 'V = B*h', { V: ['volume', 'units³'], B: ['area of the base', 'units²'], h: ['height', 'units'] }),

    // ---------- trig
    F('trig', 'Sine (right triangle)', 'sin(theta) = opp/hyp', { theta: ['angle', '°'], opp: ['side opposite the angle', 'units'], hyp: ['hypotenuse', 'units'] }),
    F('trig', 'Cosine (right triangle)', 'cos(theta) = adj/hyp', { theta: ['angle', '°'], adj: ['side next to the angle', 'units'], hyp: ['hypotenuse', 'units'] }),
    F('trig', 'Tangent (right triangle)', 'tan(theta) = opp/adj', { theta: ['angle', '°'], opp: ['side opposite the angle', 'units'], adj: ['side next to the angle', 'units'] }),
    F('trig', 'Law of sines', 'a/sin(A) = b/sin(B)', { a: ['side a', 'units'], A: ['angle opposite a', '°'], b: ['side b', 'units'], B: ['angle opposite b', '°'] }),
    F('trig', 'Law of cosines', 'c^2 = a^2 + b^2 - 2*a*b*cos(C)', { c: ['side c', 'units'], a: ['side a', 'units'], b: ['side b', 'units'], C: ['angle between a and b', '°'] }),
    F('trig', 'Triangle area from two sides and the angle between', 'A = 1/2*a*b*sin(C)', { A: ['area', 'units²'], a: ['side a', 'units'], b: ['side b', 'units'], C: ['angle between them', '°'] }),
    F('trig', 'Degrees to radians', 'rad = deg*pi/180', { rad: ['radians', 'rad'], deg: ['degrees', '°'] }),

    // ---------- algebra
    F('alg', 'Quadratic equation', 'a*x^2 + b*x + c = 0', { x: ['unknown', ''], a: ['x² coefficient', ''], b: ['x coefficient', ''], c: ['constant', ''] }, 'Can have two answers, one, or none.'),
    F('alg', 'Straight line', 'y = m*x + b', { y: ['y', ''], m: ['slope', ''], x: ['x', ''], b: ['y-intercept', ''] }),
    F('alg', 'Slope between two points', 'm = (y_2 - y_1)/(x_2 - x_1)', { m: ['slope', ''], x_1: ['x of point 1', ''], y_1: ['y of point 1', ''], x_2: ['x of point 2', ''], y_2: ['y of point 2', ''] }),
    F('alg', 'Distance between two points', 'd = sqrt((x_2 - x_1)^2 + (y_2 - y_1)^2)', { d: ['distance', ''], x_1: ['x of point 1', ''], y_1: ['y of point 1', ''], x_2: ['x of point 2', ''], y_2: ['y of point 2', ''] }),
    F('alg', 'Midpoint (x)', 'x_m = (x_1 + x_2)/2', { x_m: ['middle x', ''], x_1: ['x of point 1', ''], x_2: ['x of point 2', ''] }),
    F('alg', 'Percent change', 'p = (new - old)/old*100', { p: ['percent change', '%'], new: ['new value', ''], old: ['old value', ''] }),
    F('alg', 'Percent of', 'part = p/100*whole', { part: ['part', ''], p: ['percent', '%'], whole: ['whole', ''] }),
    F('alg', 'Direct proportion', 'y = k*x', { y: ['y', ''], k: ['constant', ''], x: ['x', ''] }),
    F('alg', 'Average of two numbers', 'avg = (a + b)/2', { avg: ['average', ''], a: ['first', ''], b: ['second', ''] }),

    // ---------- electrical
    F('elec', "Ohm's law", 'V = I*R', { V: ['voltage', 'V'], I: ['current', 'A'], R: ['resistance', 'Ω'] }),
    F('elec', 'Power (voltage × current)', 'P = V*I', { P: ['power', 'W'], V: ['voltage', 'V'], I: ['current', 'A'] }),
    F('elec', 'Power from current and resistance', 'P = I^2*R', { P: ['power', 'W'], I: ['current', 'A'], R: ['resistance', 'Ω'] }),
    F('elec', 'Power from voltage and resistance', 'P = V^2/R', { P: ['power', 'W'], V: ['voltage', 'V'], R: ['resistance', 'Ω'] }),
    F('elec', 'Resistors in series (2)', 'R_t = R_1 + R_2', { R_t: ['total resistance', 'Ω'], R_1: ['resistor 1', 'Ω'], R_2: ['resistor 2', 'Ω'] }),
    F('elec', 'Resistors in series (3)', 'R_t = R_1 + R_2 + R_3', { R_t: ['total resistance', 'Ω'], R_1: ['resistor 1', 'Ω'], R_2: ['resistor 2', 'Ω'], R_3: ['resistor 3', 'Ω'] }),
    F('elec', 'Resistors in parallel (2)', '1/R_t = 1/R_1 + 1/R_2', { R_t: ['total resistance', 'Ω'], R_1: ['resistor 1', 'Ω'], R_2: ['resistor 2', 'Ω'] }),
    F('elec', 'Resistors in parallel (3)', '1/R_t = 1/R_1 + 1/R_2 + 1/R_3', { R_t: ['total resistance', 'Ω'], R_1: ['resistor 1', 'Ω'], R_2: ['resistor 2', 'Ω'], R_3: ['resistor 3', 'Ω'] }),
    F('elec', 'Voltage divider', 'V_out = V_in*R_2/(R_1 + R_2)', { V_out: ['output voltage', 'V'], V_in: ['input voltage', 'V'], R_1: ['top resistor', 'Ω'], R_2: ['bottom resistor', 'Ω'] }),
    F('elec', 'LED series resistor', 'R = (V_s - V_f)/I', { R: ['resistor', 'Ω'], V_s: ['supply voltage', 'V'], V_f: ['LED forward voltage', 'V'], I: ['LED current', 'A'] }),
    F('elec', 'Capacitor charge', 'Q = C*V', { Q: ['charge', 'C (coulombs)'], C: ['capacitance', 'F'], V: ['voltage', 'V'] }),
    F('elec', 'Capacitor energy', 'E = 1/2*C*V^2', { E: ['energy', 'J'], C: ['capacitance', 'F'], V: ['voltage', 'V'] }),
    F('elec', 'Capacitors in parallel (2)', 'C_t = C_1 + C_2', { C_t: ['total capacitance', 'F'], C_1: ['capacitor 1', 'F'], C_2: ['capacitor 2', 'F'] }),
    F('elec', 'Capacitors in series (2)', '1/C_t = 1/C_1 + 1/C_2', { C_t: ['total capacitance', 'F'], C_1: ['capacitor 1', 'F'], C_2: ['capacitor 2', 'F'] }),
    F('elec', 'RC time constant', 'tau = R*C', { tau: ['time constant', 's'], R: ['resistance', 'Ω'], C: ['capacitance', 'F'] }),
    F('elec', 'Capacitor charging voltage', 'V_c = V_s*(1 - e^(-t/(R*C)))', { V_c: ['capacitor voltage', 'V'], V_s: ['supply voltage', 'V'], t: ['time', 's'], R: ['resistance', 'Ω'], C: ['capacitance', 'F'] }),
    F('elec', 'Capacitive reactance', 'X_C = 1/(2*pi*f*C)', { X_C: ['reactance', 'Ω'], f: ['frequency', 'Hz'], C: ['capacitance', 'F'] }),
    F('elec', 'Inductive reactance', 'X_L = 2*pi*f*L', { X_L: ['reactance', 'Ω'], f: ['frequency', 'Hz'], L: ['inductance', 'H'] }),
    F('elec', 'Impedance (series RLC)', 'Z = sqrt(R^2 + (X_L - X_C)^2)', { Z: ['impedance', 'Ω'], R: ['resistance', 'Ω'], X_L: ['inductive reactance', 'Ω'], X_C: ['capacitive reactance', 'Ω'] }),
    F('elec', 'Resonant frequency (LC)', 'f = 1/(2*pi*sqrt(L*C))', { f: ['frequency', 'Hz'], L: ['inductance', 'H'], C: ['capacitance', 'F'] }),
    F('elec', 'Frequency and period', 'f = 1/T', { f: ['frequency', 'Hz'], T: ['period', 's'] }),
    F('elec', 'Electrical energy', 'E = P*t', { E: ['energy', 'J (or Wh if t is in hours)'], P: ['power', 'W'], t: ['time', 's'] }),
    F('elec', 'Wire resistance', 'R = rho*L/A', { R: ['resistance', 'Ω'], rho: ['resistivity (copper ≈ 1.68e-8)', 'Ω·m'], L: ['length', 'm'], A: ['cross-section area', 'm²'] }),
    F('elec', 'AC power with power factor', 'P = V*I*pf', { P: ['real power', 'W'], V: ['RMS voltage', 'V'], I: ['RMS current', 'A'], pf: ['power factor (0–1)', ''] }),
    F('elec', 'RMS from peak voltage', 'V_rms = V_peak/sqrt(2)', { V_rms: ['RMS voltage', 'V'], V_peak: ['peak voltage', 'V'] }),
    F('elec', 'Transformer turns ratio', 'V_s/V_p = N_s/N_p', { V_s: ['secondary voltage', 'V'], V_p: ['primary voltage', 'V'], N_s: ['secondary turns', ''], N_p: ['primary turns', ''] }),

    // ---------- physics
    F('phys', 'Speed', 'v = d/t', { v: ['speed', 'm/s'], d: ['distance', 'm'], t: ['time', 's'] }),
    F('phys', "Newton's second law", 'F = m*a', { F: ['force', 'N'], m: ['mass', 'kg'], a: ['acceleration', 'm/s²'] }),
    F('phys', 'Weight', 'W = m*g', { W: ['weight', 'N'], m: ['mass', 'kg'], g: ['gravity', 'm/s²', 9.81] }),
    F('phys', 'Kinetic energy', 'KE = 1/2*m*v^2', { KE: ['kinetic energy', 'J'], m: ['mass', 'kg'], v: ['speed', 'm/s'] }),
    F('phys', 'Potential energy', 'PE = m*g*h', { PE: ['potential energy', 'J'], m: ['mass', 'kg'], g: ['gravity', 'm/s²', 9.81], h: ['height', 'm'] }),
    F('phys', 'Work', 'W = F*d', { W: ['work', 'J'], F: ['force', 'N'], d: ['distance', 'm'] }),
    F('phys', 'Power (work per time)', 'P = W/t', { P: ['power', 'W'], W: ['work', 'J'], t: ['time', 's'] }),
    F('phys', 'Momentum', 'p = m*v', { p: ['momentum', 'kg·m/s'], m: ['mass', 'kg'], v: ['velocity', 'm/s'] }),
    F('phys', 'Final speed', 'v = u + a*t', { v: ['final speed', 'm/s'], u: ['starting speed', 'm/s'], a: ['acceleration', 'm/s²'], t: ['time', 's'] }),
    F('phys', 'Distance with acceleration', 's = u*t + 1/2*a*t^2', { s: ['distance', 'm'], u: ['starting speed', 'm/s'], a: ['acceleration', 'm/s²'], t: ['time', 's'] }),
    F('phys', 'Speed after a distance', 'v^2 = u^2 + 2*a*s', { v: ['final speed', 'm/s'], u: ['starting speed', 'm/s'], a: ['acceleration', 'm/s²'], s: ['distance', 'm'] }),
    F('phys', 'Density', 'rho = m/V', { rho: ['density', 'kg/m³'], m: ['mass', 'kg'], V: ['volume', 'm³'] }),
    F('phys', 'Pressure', 'P = F/A', { P: ['pressure', 'Pa'], F: ['force', 'N'], A: ['area', 'm²'] }),
    F('phys', 'Wave speed', 'v = f*lambda', { v: ['wave speed', 'm/s'], f: ['frequency', 'Hz'], lambda: ['wavelength', 'm'] }),
    F('phys', "Hooke's law (spring)", 'F = k*x', { F: ['force', 'N'], k: ['spring constant', 'N/m'], x: ['stretch', 'm'] }),
    F('phys', 'Centripetal acceleration', 'a = v^2/r', { a: ['acceleration', 'm/s²'], v: ['speed', 'm/s'], r: ['radius', 'm'] }),
    F('phys', 'Torque', 'T = F*r', { T: ['torque', 'N·m'], F: ['force', 'N'], r: ['lever arm', 'm'] }),
    F('phys', 'Heat energy', 'Q = m*c*dT', { Q: ['heat', 'J'], m: ['mass', 'kg'], c: ['specific heat (water ≈ 4186)', 'J/(kg·°C)'], dT: ['temperature change', '°C'] }),
    F('phys', 'Lever balance', 'F_1*d_1 = F_2*d_2', { F_1: ['force 1', 'N'], d_1: ['distance 1 from pivot', 'm'], F_2: ['force 2', 'N'], d_2: ['distance 2 from pivot', 'm'] }),
    F('phys', 'Gear ratio', 'ratio = N_out/N_in', { ratio: ['gear ratio', ''], N_out: ['teeth on driven gear', ''], N_in: ['teeth on driving gear', ''] }),

    // ---------- chemistry
    F('chem', 'Ideal gas law', 'P*V = n*R*T', { P: ['pressure', 'Pa'], V: ['volume', 'm³'], n: ['amount', 'mol'], R: ['gas constant', 'J/(mol·K)', 8.314], T: ['temperature', 'K'] }),
    F('chem', 'Moles from mass', 'n = m/M', { n: ['amount', 'mol'], m: ['mass', 'g'], M: ['molar mass', 'g/mol'] }),
    F('chem', 'Molarity', 'c = n/V', { c: ['concentration', 'mol/L'], n: ['amount', 'mol'], V: ['volume', 'L'] }),
    F('chem', 'Dilution', 'c_1*V_1 = c_2*V_2', { c_1: ['starting concentration', 'mol/L'], V_1: ['starting volume', 'L'], c_2: ['final concentration', 'mol/L'], V_2: ['final volume', 'L'] }),
    F('chem', 'Boyle’s law', 'P_1*V_1 = P_2*V_2', { P_1: ['pressure before', ''], V_1: ['volume before', ''], P_2: ['pressure after', ''], V_2: ['volume after', ''] }),
    F('chem', 'pH', 'pH = -log(H)', { pH: ['pH', ''], H: ['hydrogen ion concentration', 'mol/L'] }),

    // ---------- money
    F('money', 'Simple interest', 'I = P*r*t', { I: ['interest', '$'], P: ['principal', '$'], r: ['rate per year (5% = 0.05)', ''], t: ['time', 'years'] }),
    F('money', 'Compound interest', 'A = P*(1 + r/n)^(n*t)', { A: ['final amount', '$'], P: ['principal', '$'], r: ['rate per year (5% = 0.05)', ''], n: ['compounds per year', '', 12], t: ['time', 'years'] }),
    F('money', 'Loan payment (monthly)', 'M = P*(r/12)/(1 - (1 + r/12)^(-n))', { M: ['monthly payment', '$'], P: ['loan amount', '$'], r: ['yearly rate (6% = 0.06)', ''], n: ['number of payments', 'months'] }),
    F('money', 'Discount price', 'price = original*(1 - d/100)', { price: ['sale price', '$'], original: ['original price', '$'], d: ['discount', '%'] }),
    F('money', 'Tip', 'tip = bill*p/100', { tip: ['tip', '$'], bill: ['bill', '$'], p: ['tip percent', '%'] }),
    F('money', 'Unit price', 'u = price/qty', { u: ['price per unit', '$'], price: ['total price', '$'], qty: ['quantity', ''] }),

    // ---------- everyday
    F('every', 'Celsius ↔ Fahrenheit', 'F = 9/5*C + 32', { F: ['Fahrenheit', '°F'], C: ['Celsius', '°C'] }),
    F('every', 'Celsius ↔ Kelvin', 'K = C + 273.15', { K: ['Kelvin', 'K'], C: ['Celsius', '°C'] }),
    F('every', 'Distance, speed, time', 'd = s*t', { d: ['distance', 'km or mi'], s: ['speed', 'km/h or mph'], t: ['time', 'hours'] }),
    F('every', 'Fuel economy', 'mpg = miles/gallons', { mpg: ['fuel economy', 'mi/gal'], miles: ['distance', 'mi'], gallons: ['fuel used', 'gal'] }),
    F('every', 'Inches ↔ centimetres', 'cm = 2.54*inch', { cm: ['centimetres', 'cm'], inch: ['inches', 'in'] }),
    F('every', 'Pounds ↔ kilograms', 'kg = 0.45359237*lb', { kg: ['kilograms', 'kg'], lb: ['pounds', 'lb'] }),
    F('every', 'Miles ↔ kilometres', 'km = 1.609344*mi', { km: ['kilometres', 'km'], mi: ['miles', 'mi'] }),
    F('every', 'Body mass index', 'BMI = kg/m^2', { BMI: ['BMI', ''], kg: ['weight', 'kg'], m: ['height', 'm'] }),
    F('every', 'Roof or ramp pitch', 'pitch = rise/run*12', { pitch: ['pitch (inches per foot)', 'in/ft'], rise: ['rise', ''], run: ['run', ''] }),
    F('every', 'Paint / flooring needed', 'amount = area/coverage', { amount: ['amount needed', ''], area: ['area to cover', 'ft² or m²'], coverage: ['coverage per unit', ''] })
  ];

  LIST.forEach((f, i) => { f.id = 'f' + i; });
  return { CATEGORIES, LIST };
});
