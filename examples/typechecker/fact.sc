fun fac(m [Nat]) [returns Nat] = {
  [decreases m]
  if (m == 0) 1
  else m * fac(m - 1)
}

fac(2)
