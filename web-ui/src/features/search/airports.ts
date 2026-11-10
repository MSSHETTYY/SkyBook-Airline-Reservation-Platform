/**
 * Airports people can search by city, airport name, country, former city name or IATA code.
 * Customers remember "Dubai" or "London", not "DXB" or "LHR"; the form still sends the code.
 */
export interface Airport {
  code: string;
  city: string;
  name: string;
  country: string;
  /** Other names people type: former city names, local spellings. */
  aliases?: string[];
}

export const AIRPORTS: readonly Airport[] = [
  { code: 'DXB', city: 'Dubai', name: 'Dubai International', country: 'United Arab Emirates' },
  { code: 'AUH', city: 'Abu Dhabi', name: 'Zayed International', country: 'United Arab Emirates' },
  { code: 'DOH', city: 'Doha', name: 'Hamad International', country: 'Qatar' },
  { code: 'LHR', city: 'London', name: 'Heathrow', country: 'United Kingdom' },
  { code: 'LGW', city: 'London', name: 'Gatwick', country: 'United Kingdom' },
  { code: 'MAN', city: 'Manchester', name: 'Manchester Airport', country: 'United Kingdom' },
  { code: 'DUB', city: 'Dublin', name: 'Dublin Airport', country: 'Ireland' },
  { code: 'CDG', city: 'Paris', name: 'Charles de Gaulle', country: 'France' },
  { code: 'FRA', city: 'Frankfurt', name: 'Frankfurt Airport', country: 'Germany' },
  { code: 'MUC', city: 'Munich', name: 'Munich Airport', country: 'Germany', aliases: ['München'] },
  { code: 'AMS', city: 'Amsterdam', name: 'Schiphol', country: 'Netherlands' },
  { code: 'ZRH', city: 'Zurich', name: 'Zurich Airport', country: 'Switzerland', aliases: ['Zürich'] },
  { code: 'MAD', city: 'Madrid', name: 'Adolfo Suárez Madrid-Barajas', country: 'Spain' },
  { code: 'FCO', city: 'Rome', name: 'Fiumicino', country: 'Italy', aliases: ['Roma'] },
  { code: 'IST', city: 'Istanbul', name: 'Istanbul Airport', country: 'Türkiye', aliases: ['Turkey'] },
  { code: 'CAI', city: 'Cairo', name: 'Cairo International', country: 'Egypt' },
  { code: 'JNB', city: 'Johannesburg', name: 'O. R. Tambo International', country: 'South Africa' },
  { code: 'NBO', city: 'Nairobi', name: 'Jomo Kenyatta International', country: 'Kenya' },
  { code: 'BOM', city: 'Mumbai', name: 'Chhatrapati Shivaji Maharaj International', country: 'India',
    aliases: ['Bombay'] },
  { code: 'DEL', city: 'Delhi', name: 'Indira Gandhi International', country: 'India', aliases: ['New Delhi'] },
  { code: 'BLR', city: 'Bengaluru', name: 'Kempegowda International', country: 'India', aliases: ['Bangalore'] },
  { code: 'MAA', city: 'Chennai', name: 'Chennai International', country: 'India', aliases: ['Madras'] },
  { code: 'HYD', city: 'Hyderabad', name: 'Rajiv Gandhi International', country: 'India' },
  { code: 'CCU', city: 'Kolkata', name: 'Netaji Subhas Chandra Bose International', country: 'India',
    aliases: ['Calcutta'] },
  { code: 'COK', city: 'Kochi', name: 'Cochin International', country: 'India', aliases: ['Cochin'] },
  { code: 'MLR', city: 'Mangaluru', name: 'Mangaluru International', country: 'India', aliases: ['Mangalore'] },
  { code: 'KHI', city: 'Karachi', name: 'Jinnah International', country: 'Pakistan' },
  { code: 'CMB', city: 'Colombo', name: 'Bandaranaike International', country: 'Sri Lanka' },
  { code: 'SIN', city: 'Singapore', name: 'Changi', country: 'Singapore' },
  { code: 'KUL', city: 'Kuala Lumpur', name: 'Kuala Lumpur International', country: 'Malaysia' },
  { code: 'BKK', city: 'Bangkok', name: 'Suvarnabhumi', country: 'Thailand' },
  { code: 'HKG', city: 'Hong Kong', name: 'Hong Kong International', country: 'Hong Kong' },
  { code: 'NRT', city: 'Tokyo', name: 'Narita', country: 'Japan' },
  { code: 'HND', city: 'Tokyo', name: 'Haneda', country: 'Japan' },
  { code: 'SYD', city: 'Sydney', name: 'Kingsford Smith', country: 'Australia' },
  { code: 'JFK', city: 'New York', name: 'John F. Kennedy International', country: 'United States',
    aliases: ['NYC'] },
  { code: 'EWR', city: 'New York', name: 'Newark Liberty International', country: 'United States',
    aliases: ['Newark', 'NYC'] },
  { code: 'BOS', city: 'Boston', name: 'Logan International', country: 'United States' },
  { code: 'ORD', city: 'Chicago', name: "O'Hare International", country: 'United States' },
  { code: 'MIA', city: 'Miami', name: 'Miami International', country: 'United States' },
  { code: 'LAX', city: 'Los Angeles', name: 'Los Angeles International', country: 'United States' },
  { code: 'SFO', city: 'San Francisco', name: 'San Francisco International', country: 'United States' },
  { code: 'YYZ', city: 'Toronto', name: 'Pearson International', country: 'Canada' },
  { code: 'GRU', city: 'São Paulo', name: 'Guarulhos International', country: 'Brazil', aliases: ['Sao Paulo'] },
  { code: 'MEX', city: 'Mexico City', name: 'Benito Juárez International', country: 'Mexico' },
];

/** Routes SkyBook flies daily; offered as one-click suggestions on the search page. */
export const POPULAR_ROUTES: ReadonlyArray<readonly [string, string]> = [
  ['DXB', 'LHR'],
  ['LHR', 'DXB'],
  ['DXB', 'BOM'],
  ['BOM', 'DXB'],
  ['LHR', 'JFK'],
];

const BY_CODE = new Map(AIRPORTS.map((airport) => [airport.code, airport]));

export function findAirport(code: string | null | undefined): Airport | undefined {
  return code ? BY_CODE.get(code.toUpperCase()) : undefined;
}

/** "Dubai (DXB)"; unknown codes are shown as they are. */
export function airportLabel(code: string): string {
  const airport = findAirport(code);
  return airport ? `${airport.city} (${airport.code})` : code;
}

/** Lower case without accents, so "zurich" finds "Zürich" and "sao" finds "São Paulo". */
export function normalise(text: string): string {
  return text.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase().trim();
}

/**
 * Best matches first: exact code, then city starting with the query, then words of the city, airport name,
 * aliases or country starting with it, then anything containing it.
 */
export function searchAirports(query: string, limit = 8): Airport[] {
  const q = normalise(query);
  if (!q) return [...AIRPORTS].slice(0, limit);
  const score = (airport: Airport): number => {
    const city = normalise(airport.city);
    const words = [airport.city, airport.name, airport.country, ...(airport.aliases ?? [])]
      .flatMap((text) => normalise(text).split(/[\s.'-]+/));
    const everything = normalise([airport.code, airport.city, airport.name, airport.country,
      ...(airport.aliases ?? [])].join(' '));
    if (airport.code.toLowerCase() === q) return 0;
    if (city.startsWith(q)) return 1;
    if ((airport.aliases ?? []).some((alias) => normalise(alias).startsWith(q))) return 2;
    if (words.some((word) => word.startsWith(q))) return 3;
    if (everything.includes(q)) return 4;
    return Number.POSITIVE_INFINITY;
  };
  return AIRPORTS.map((airport) => ({ airport, rank: score(airport) }))
    .filter(({ rank }) => Number.isFinite(rank))
    .sort((a, b) => a.rank - b.rank || a.airport.city.localeCompare(b.airport.city))
    .slice(0, limit)
    .map(({ airport }) => airport);
}
