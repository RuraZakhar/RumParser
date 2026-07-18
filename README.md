# RumParser

RumParser is a Java Maven application designed to scrape and match rum data from various sources including RumRatings, The Rum Howler Blog, and Silpo. The application filters the scraped data to output top-rated rums.

## Features

- Scrapes rum information and ratings from multiple sources.
- Matches and aggregates data to create a comprehensive view of rum products.
- Filters rums to output those with high ratings.
- Outputs the structured data to a JSON file (`top_rum_products.json`).

## Requirements

- Java 17
- Maven

## Key Dependencies

- **Gson:** JSON processing.
- **Jsoup:** HTML scraping.
- **Lombok:** Code generation.

## Configuration

Configuration is managed via environment variables. An example configuration file is provided as `.env.example`.

You can set these variables in your environment before running the application:

- `FIRECRAWL_API_KEY`: API key for Firecrawl.
- `MAX_RUM_RATINGS_PAGES`: Maximum number of RumRatings pages scraped in one run.
- `MAX_HOWLER_PRODUCTS`: Maximum number of Rum Howler products fetched in one run.
- `MAX_SILPO_LOOKUPS_PER_RUN`: Maximum number of previously unpriced products sent to Silpo lookup per run.

## Build and Run

To compile the project, run the following Maven command:

```bash
mvn clean compile
```

To execute the main application:

```bash
mvn exec:java -Dexec.mainClass="rum.parser.Main"
```

## Output

The application reads from and writes to `top_rum_products.json` in the root directory. It stores a list of unique, highly-rated rums along with their ratings, prices, and links to the source websites.
