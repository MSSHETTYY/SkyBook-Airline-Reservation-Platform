import { AIRPORTS, airportLabel, findAirport, normalise, POPULAR_ROUTES, searchAirports } from './airports';

const codes = (query: string) => searchAirports(query).map((airport) => airport.code);

describe('airport search', () => {
  it('finds airports by city, with the city match first', () => {
    // An exact code wins (DUB is Dublin's code), then cities starting with the text.
    expect(codes('dub').slice(0, 2)).toEqual(['DUB', 'DXB']);
    expect(codes('duba')[0]).toBe('DXB');
    expect(codes('London').slice(0, 2).sort()).toEqual(['LGW', 'LHR']);
    expect(codes('new york')).toEqual(expect.arrayContaining(['JFK', 'EWR']));
  });

  it('understands codes, airport names, former city names and countries', () => {
    expect(codes('lhr')[0]).toBe('LHR');
    expect(codes('heathrow')).toEqual(['LHR']);
    expect(codes('Bombay')[0]).toBe('BOM');
    expect(codes('bangalore')[0]).toBe('BLR');
    expect(codes('india')).toEqual(expect.arrayContaining(['BOM', 'DEL', 'MAA']));
  });

  it('ignores accents and case', () => {
    expect(normalise('  São Paulo ')).toBe('sao paulo');
    expect(codes('zurich')).toEqual(['ZRH']);
    expect(codes('MÜNCHEN')).toEqual(['MUC']);
  });

  it('returns nothing for unknown places and a short list for an empty query', () => {
    expect(codes('atlantis')).toEqual([]);
    expect(searchAirports('')).toHaveLength(8);
    expect(searchAirports('a', 3)).toHaveLength(3);
  });

  it('labels codes with their city', () => {
    expect(airportLabel('dxb')).toBe('Dubai (DXB)');
    expect(airportLabel('XXX')).toBe('XXX');
    expect(findAirport(undefined)).toBeUndefined();
  });

  it('only suggests popular routes between known airports, with unique codes', () => {
    expect(new Set(AIRPORTS.map((a) => a.code)).size).toBe(AIRPORTS.length);
    for (const [from, to] of POPULAR_ROUTES) {
      expect(findAirport(from)).toBeDefined();
      expect(findAirport(to)).toBeDefined();
    }
  });
});
